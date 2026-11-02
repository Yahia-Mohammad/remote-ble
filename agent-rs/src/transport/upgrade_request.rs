//! Reads a WebSocket upgrade request ahead of tungstenite's handshake.
//!
//! tungstenite decides admission in a synchronous callback, but the duplicate-session gate has to
//! wait for an incumbent that is still retiring. So the agent reads and parses the request head
//! itself, decides admission with whatever awaiting that takes, and then hands tungstenite a
//! stream that replays every byte already read. tungstenite parses the same bytes again and so
//! reaches the same request, or the same error, it would have reached alone.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, ReadBuf};
use tokio_tungstenite::tungstenite::Error;
use tokio_tungstenite::tungstenite::handshake::machine::TryParse;
use tokio_tungstenite::tungstenite::handshake::server::{Request, create_response};

/// tungstenite's own cap on a request head; a larger one is treated as an attack, as there.
const MAX_REQUEST_HEAD_BYTES: usize = 65_536;

/// Reads until the request head is complete, the peer closes, or the head fails to parse.
///
/// The request comes back only when tungstenite would get as far as its callback with it: a
/// complete head that is a valid WebSocket upgrade. Otherwise it is `None` and tungstenite, reading
/// the replayed bytes, fails the handshake exactly as it did before this reader existed.
pub(crate) async fn read_upgrade_request<S>(
    mut stream: S,
) -> Result<(Replay<S>, Option<Request>), Error>
where
    S: AsyncRead + Unpin,
{
    let mut head = Vec::new();
    let mut chunk = [0u8; 4096];
    let request = loop {
        let read = stream.read(&mut chunk).await?;
        if read == 0 {
            break None;
        }
        head.extend_from_slice(&chunk[..read]);
        if head.len() > MAX_REQUEST_HEAD_BYTES {
            return Err(Error::AttackAttempt);
        }
        match Request::try_parse(&head) {
            Ok(Some((_, request))) => break create_response(&request).is_ok().then_some(request),
            Ok(None) => continue,
            Err(_) => break None,
        }
    };
    Ok((Replay::new(head, stream), request))
}

/// A stream that yields bytes already read from it before reading on.
pub(crate) struct Replay<S> {
    prefix: Vec<u8>,
    consumed: usize,
    inner: S,
}

impl<S> Replay<S> {
    fn new(prefix: Vec<u8>, inner: S) -> Self {
        Self {
            prefix,
            consumed: 0,
            inner,
        }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Replay<S> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.consumed < this.prefix.len() {
            let end = this.prefix.len().min(this.consumed + buf.remaining());
            buf.put_slice(&this.prefix[this.consumed..end]);
            this.consumed = end;
            if this.consumed == this.prefix.len() {
                // The socket lives on for the whole session; don't keep the head with it.
                this.prefix = Vec::new();
                this.consumed = 0;
            }
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut this.inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Replay<S> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, buf)
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write_vectored(cx, bufs)
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    use tokio_tungstenite::tungstenite::handshake::client::generate_key;

    fn upgrade() -> Vec<u8> {
        format!(
            "GET /agent HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\n\
             Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n\
             Sec-WebSocket-Key: {}\r\n\r\n",
            generate_key()
        )
        .into_bytes()
    }

    async fn read_from(bytes: &[u8], split_at: usize) -> (Vec<u8>, Option<Request>) {
        let (mut client, server) = tokio::io::duplex(1024);
        let bytes = bytes.to_vec();
        let writer = tokio::spawn(async move {
            client.write_all(&bytes[..split_at]).await.unwrap();
            client.flush().await.unwrap();
            tokio::task::yield_now().await;
            client.write_all(&bytes[split_at..]).await.unwrap();
        });
        let (mut replay, request) = read_upgrade_request(server).await.unwrap();
        writer.await.unwrap();
        let mut replayed = Vec::new();
        replay.read_to_end(&mut replayed).await.unwrap();
        (replayed, request)
    }

    #[tokio::test]
    async fn a_head_split_across_reads_is_parsed_and_replayed_whole() {
        let upgrade = upgrade();
        let (replayed, request) = read_from(&upgrade, 20).await;
        assert_eq!(request.expect("a valid upgrade").uri(), "/agent");
        assert_eq!(replayed, upgrade);
    }

    #[tokio::test]
    async fn a_request_tungstenite_would_refuse_is_replayed_without_a_request() {
        let plain = b"GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        let (replayed, request) = read_from(plain, plain.len()).await;
        assert!(request.is_none());
        assert_eq!(replayed, plain);

        let (replayed, request) = read_from(b"NOT HTTP\r\n\r\n", 4).await;
        assert!(request.is_none());
        assert_eq!(replayed, b"NOT HTTP\r\n\r\n");
    }

    #[tokio::test]
    async fn an_oversized_head_is_refused_as_tungstenite_refuses_it() {
        let (mut client, server) = tokio::io::duplex(1024);
        let writer = tokio::spawn(async move {
            let _ = client
                .write_all(b"GET /agent HTTP/1.1\r\nX-Padding: ")
                .await;
            let _ = client.write_all(&[b'a'; MAX_REQUEST_HEAD_BYTES]).await;
        });
        assert!(matches!(
            read_upgrade_request(server).await,
            Err(Error::AttackAttempt)
        ));
        writer.await.unwrap();
    }
}
