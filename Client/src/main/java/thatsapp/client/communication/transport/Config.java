package thatsapp.client.communication.transport;

public record Config(String host, int port, String userName, String accessKey, String roomSecret, String sessionName, boolean autoCreate) {
}
