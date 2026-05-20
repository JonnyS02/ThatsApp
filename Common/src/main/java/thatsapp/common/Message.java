package thatsapp.common;

import java.io.Serializable;

public record Message(String message, int senderId, String status) implements Serializable {
}
