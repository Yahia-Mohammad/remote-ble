use crate::protocol::{
    errors::AgentError,
    events::AgentEvent,
    op::{CharRef, DeviceHandle, ScanFilter},
    results::ResultPayload,
};
use async_trait::async_trait;
use tokio::sync::mpsc;

/// Identifies a client-owned streaming resource inside the agent. Protocol stream IDs are only
/// unique within one WebSocket connection, so the backend must never use them as global keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct StreamKey {
    pub connection: u64,
    pub local_id: i64,
}

/// Reserved backend subscription used by the agent-lifetime scan coordinator.  Backends must send
/// raw advertisements to this stream: identity merging and logical filtering happen in the
/// coordinator, where replay and memory bounds are enforced.
pub const COORDINATOR_SCAN_STREAM: StreamKey = StreamKey {
    connection: 0,
    local_id: i64::MIN,
};

#[async_trait]
pub trait BleBackend: Send + Sync {
    fn capabilities(&self) -> Vec<String>;
    async fn start_scan(
        &self,
        stream: StreamKey,
        filters: Vec<ScanFilter>,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError>;
    async fn stop_scan(&self, stream: StreamKey) -> Result<(), AgentError>;
    /// Releases every scan/observation owned by a retired WebSocket connection.
    async fn stop_connection_streams(&self, connection: u64) -> Result<(), AgentError>;
    async fn connect(
        &self,
        device: &DeviceHandle,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError>;
    async fn disconnect(&self, device: &DeviceHandle) -> Result<(), AgentError>;
    async fn discover(&self, device: &DeviceHandle) -> Result<ResultPayload, AgentError>;
    async fn read(
        &self,
        device: &DeviceHandle,
        char_ref: &CharRef,
    ) -> Result<ResultPayload, AgentError>;
    async fn write(
        &self,
        device: &DeviceHandle,
        char_ref: &CharRef,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), AgentError>;
    async fn request_mtu(
        &self,
        device: &DeviceHandle,
        mtu: i32,
    ) -> Result<ResultPayload, AgentError>;
    async fn start_observe(
        &self,
        stream: StreamKey,
        device: &DeviceHandle,
        char_ref: &CharRef,
        event_tx: mpsc::Sender<AgentEvent>,
    ) -> Result<(), AgentError>;
    async fn stop_observe(&self, stream: StreamKey) -> Result<(), AgentError>;
}
