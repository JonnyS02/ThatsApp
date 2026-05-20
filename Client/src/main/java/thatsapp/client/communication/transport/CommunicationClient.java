package thatsapp.client.communication.transport;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.common.FilePacket;

import java.io.IOException;
import java.util.concurrent.Executor;

public interface CommunicationClient {

    void connect(Config config, CommunicationClientListener listener, Executor callbackExecutor);

    void requestCancel();

    void disconnect();

    SymmetricEncryption getSymmetric();

    void sendMessage(String message);

    void sendTyping(long ttlMs);

    void sendFilePacket(FilePacket packet) throws IOException;
}
