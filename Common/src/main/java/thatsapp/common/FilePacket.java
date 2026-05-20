package thatsapp.common;

import java.io.Serializable;

public record FilePacket(String status, String fileId, String fileName, long fileSize, int chunkIndex, int totalChunks,
                         String payload, int senderId, String reason) implements Serializable {

}
