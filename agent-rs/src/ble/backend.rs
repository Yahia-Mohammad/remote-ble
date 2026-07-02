use crate::protocol::{
    errors::AgentError,
    events::AgentEvent,
    op::{CharRef, DeviceHandle},
    results::ResultPayload,
};
use async_trait::async_trait;
use tokio::sync::mpsc;

#[async_trait]
pub trait BleBackend: Send + Sync {
    fn capabilities(&self) -> Vec<String>;
    async fn start_scan(
        &self,
        scan_id: i64,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
    ) -> Result<(), AgentError>;
    async fn stop_scan(&self, scan_id: i64) -> Result<(), AgentError>;
    async fn connect(
        &self,
        device: &DeviceHandle,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
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
        sub_id: i64,
        device: &DeviceHandle,
        char_ref: &CharRef,
        event_tx: mpsc::UnboundedSender<AgentEvent>,
    ) -> Result<(), AgentError>;
    async fn stop_observe(&self, sub_id: i64) -> Result<(), AgentError>;
}
