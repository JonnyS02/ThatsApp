package thatsapp.client.communication.transport;

import thatsapp.common.FilePacket;

import java.util.Map;

public interface CommunicationClientListener {

    void onConnected(int ownId, Map<Integer, String> users, long messageCharacterLimit, long fileSizeBytesLimit, boolean fileTransferEnabled);

    void onConnectionFailed(String reason);

    void onDisconnected();

    void onDisconnectionFailed();

    void onTextMessage(int senderId, String message);

    void onServerMessage(String message);

    void onUserJoined(int userId, String userName);

    void onUserLeft(int userId);

    void onTyping(int senderId, long expiresAt);

    void onFilePacket(FilePacket packet);

    void onShutdownRequested(int code);
}
