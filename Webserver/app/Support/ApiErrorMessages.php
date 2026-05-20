<?php

namespace App\Support;

final class ApiErrorMessages
{
    public const SESSION_NAME_CANNOT_BE_EMPTY = 'Session name cannot be empty.';
    public const SESSION_NAME_MUST_NOT_CONTAIN_SLASH = "Session name must not contain '/'.";
    public const INVALID_SESSION_NAME = 'Invalid session name';
    public const SESSION_NAME_MAX_64 = 'Session name must be at most 64 characters long.';
    public const INVALID_JSON_BODY = 'Invalid JSON body';
    public const INVALID_REQUEST = 'Invalid request';
    public const INVALID_TOKEN = 'Invalid token';
    public const INVALID_UPLOAD_KEY = 'Invalid upload key';
    public const SERVER_NOT_FOUND = 'Server not found';
    public const SESSION_NOT_FOUND = 'Session not found';
    public const ACCESS_KEY_MISMATCH = 'Access key mismatch';
    public const CHAT_ENCRYPTION_MODE_MISMATCH = 'Chat encryption mode mismatch';
    public const UNABLE_TO_HASH_ACCESS_KEY = 'Unable to hash access key.';
    public const INVALID_MESSAGE_PAYLOAD = 'Invalid message payload';
    public const INVALID_TYPING_PAYLOAD = 'Invalid typing payload';
    public const UNABLE_TO_CREATE_CACHE_DIRECTORY = 'Unable to create cache directory.';
    public const UNABLE_TO_CREATE_UPLOAD_DIRECTORY = 'Unable to create upload directory.';
    public const SESSION_LIMIT_REACHED = 'Session limit reached';
    public const UNABLE_TO_CREATE_SESSION = 'Unable to create session.';
    public const SERVER_FULL = 'Server full';
    public const UNABLE_TO_CREATE_CLIENT = 'Unable to create client.';
    public const EVENT_STREAM_ALREADY_CONNECTED = 'Event stream already connected';
    public const UPLOAD_MISSING = 'Upload missing';
    public const UNABLE_TO_WRITE_FILE = 'Unable to write file.';
    public const FILE_NOT_FOUND = 'File not found';
    public const NOT_ELIGIBLE_FOR_THIS_FILE = 'Not eligible for this file';
    public const FILE_MISSING_ON_DISK = 'File missing on disk';
    public const UNABLE_TO_CREATE_FILE_SLOT = 'Unable to create file slot.';
    public const FILE_LIMIT_REACHED = 'File count limit reached';
    public const FILE_TRANSFER_DISABLED = 'File transfer disabled';

    private function __construct()
    {
    }

    public static function messageExceedsLimit(int $characterLimit): string
    {
        return 'Message exceeds limit of ' . $characterLimit . ' characters.';
    }

    public static function fileExceedsLimit(int $fileSizeBytesLimit): string
    {
        return 'File exceeds limit of ' . intdiv($fileSizeBytesLimit, 1024 * 1024) . ' MB.';
    }
}
