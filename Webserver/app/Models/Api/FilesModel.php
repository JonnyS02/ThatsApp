<?php

namespace App\Models\Api;

use App\Support\Filesystem;
use App\Support\ApiErrorMessages;
use App\Support\ApiEventTypes;
use App\Support\TusStorage;
use Random\RandomException;

final class FilesModel extends ApiModel
{
    /**
     * @throws RandomException
     */
    public function finalizeTusUpload(
        string $sessionName,
        string $token,
        string $fileName,
        string $tempPath,
        int $fileSize,
    ): array {
        if ($tempPath === '' || !is_file($tempPath) || !TusStorage::isUploadPath($tempPath, TusStorage::uploadRoot())) {
            return ['error' => ApiErrorMessages::UPLOAD_MISSING, 'status' => 404];
        }

        $context = $this->uploadContext($sessionName, $token, $fileSize);
        if (array_key_exists('error', $context) || array_key_exists('stored', $context)) {
            $this->cleanupTempPath($tempPath);
            return $context;
        }

        $clientId = (int) $context['clientId'];
        $sessionId = (int) $context['sessionId'];
        $recipientIds = $context['recipientIds'];
        $fileSizeBytesLimit = (int) $context['fileSizeBytesLimit'];
        $fileCountLimit = (int) $context['fileCountLimit'];

        $slot = $this->createFileSlot($sessionId, $fileName, $fileSize, $fileCountLimit);
        if (array_key_exists('error', $slot)) {
            $this->cleanupTempPath($tempPath);
            return $slot;
        }
        $fileId = (int) $slot['fileId'];
        $publicId = (string) $slot['publicId'];
        $dir = (string) $slot['dir'];
        $dest = (string) $slot['path'];
        if (!@rename($tempPath, $dest)) {
            if (!@copy($tempPath, $dest)) {
                $this->deleteFileSlot($fileId, $dir);
                $this->cleanupTempPath($tempPath);
                return ['error' => ApiErrorMessages::UNABLE_TO_WRITE_FILE, 'status' => 500];
            }
        }
        $this->cleanupTempPath($tempPath);

        $actualSize = filesize($dest);
        if ($actualSize === false) {
            $actualSize = max(0, $fileSize);
        }
        if ($fileSizeBytesLimit > 0 && $actualSize > $fileSizeBytesLimit) {
            $this->deleteFileSlot($fileId, $dir);
            return ['error' => ApiErrorMessages::fileExceedsLimit($fileSizeBytesLimit), 'status' => 413];
        }
        $actualSize = $this->finalizeSize($fileId, $fileSize, $actualSize);

        $this->publishFileMeta($recipientIds, $sessionId, $clientId, $fileId);

        return $this->storedResponse($publicId, $fileName, $actualSize);
    }

    public function download(int $sessionId, int $clientId, string $publicId): array
    {
        if ($sessionId < 1 || $clientId < 1) {
            return ['error' => ApiErrorMessages::INVALID_TOKEN, 'status' => 403];
        }
        $this->touchClient($clientId);

        $file = $this->db->table('files')->where([
            'session_id' => $sessionId,
            'public_id' => $publicId,
        ])->get()->getRowArray();
        if ($file === null) {
            return ['error' => ApiErrorMessages::FILE_NOT_FOUND, 'status' => 404];
        }

        $permission = $this->db->table('file_client_permissions')->where([
            'file_id' => $file['id'],
            'client_id' => $clientId,
        ])->get()->getRowArray();
        if ($permission === null) {
            return ['error' => ApiErrorMessages::NOT_ELIGIBLE_FOR_THIS_FILE, 'status' => 403];
        }

        $fileId = (int) $file['id'];
        $path = $this->uploadDir($sessionId, $fileId) . '/' . (string) $file['name'];
        if (!is_file($path)) {
            return ['error' => ApiErrorMessages::FILE_MISSING_ON_DISK, 'status' => 404];
        }
        $permissionId = (int) $permission['id'];
        $this->db->table('file_client_permissions')->where('id', $permissionId)->delete();

        $remaining = (int) $this->db->table('file_client_permissions')->where('file_id', $fileId)->countAllResults();
        if ($remaining === 0) {
            register_shutdown_function(static function () use ($path, $sessionId, $fileId): void {
                if (is_file($path)) {
                    @unlink($path);
                }
                Filesystem::deleteDir(rtrim(WRITEPATH, '\\/') . '/uploads/' . $sessionId . '/' . $fileId);
            });
            $this->db->table('files')->where('id', $fileId)->delete();
        }

        return [
            'path' => $path,
            'name' => (string) $file['name'],
        ];
    }

    private function cleanupTempPath(string $path): void
    {
        TusStorage::deleteUploadFile($path, TusStorage::uploadRoot());
    }

    private function uploadDir(int $sessionId, int $fileId): string
    {
        return rtrim(WRITEPATH, '\\/') . '/uploads/' . $sessionId . '/' . $fileId;
    }

    /**
     * @throws RandomException
     */
    private function createFileSlot(
        int $sessionId,
        string $fileName,
        int $contentLength,
        int $fileCountLimit,
    ): array
    {
        try {
            $publicId = $this->uuidV4();
        } catch (RandomException) {
            return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_FILE_SLOT, 'status' => 500];
        }

        $this->db->transBegin();
        try {
            $lockedSession = $this->db->query(
                'SELECT id FROM sessions WHERE id = ? FOR UPDATE',
                [$sessionId],
            )->getRowArray();
            if ($lockedSession === null) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::SESSION_NOT_FOUND, 'status' => 404];
            }

            if ($fileCountLimit < 0) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::FILE_TRANSFER_DISABLED, 'status' => 403];
            }

            if ($fileCountLimit > 0) {
                $existingFiles = (int) $this->db->table('files')
                    ->where('session_id', $sessionId)
                    ->countAllResults();
                if ($existingFiles >= $fileCountLimit) {
                    $this->db->transRollback();
                    return ['error' => ApiErrorMessages::FILE_LIMIT_REACHED, 'status' => 403];
                }
            }

            $this->db->table('files')->insert([
                'public_id' => $publicId,
                'session_id' => $sessionId,
                'name' => $fileName,
                'byte_size' => max(0, $contentLength),
            ]);
            $fileId = (int) $this->db->insertID();
            if ($fileId < 1) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_FILE_SLOT, 'status' => 500];
            }

            $this->db->transCommit();
        } catch (\Throwable) {
            $this->db->transRollback();
            return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_FILE_SLOT, 'status' => 500];
        }

        $dir = $this->uploadDir($sessionId, $fileId);
        if (!is_dir($dir) && !mkdir($dir, 0777, true) && !is_dir($dir)) {
            $this->db->table('files')->where('id', $fileId)->delete();
            return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_UPLOAD_DIRECTORY, 'status' => 500];
        }

        return [
            'fileId' => $fileId,
            'publicId' => $publicId,
            'dir' => $dir,
            'path' => $dir . '/' . $fileName,
        ];
    }

    private function deleteFileSlot(int $fileId, string $dir): void
    {
        $this->db->table('files')->where('id', $fileId)->delete();
        Filesystem::deleteDir($dir);
    }

    private function finalizeSize(int $fileId, int $expectedSize, int $actualSize): int
    {
        $normalizedExpected = max(0, $expectedSize);
        if ($actualSize !== $normalizedExpected) {
            $this->db->table('files')->where('id', $fileId)->update(['byte_size' => $actualSize]);
        }
        return $actualSize;
    }

    private function publishFileMeta(array $recipientIds, int $sessionId, int $clientId, int $fileId): void
    {
        if (!empty($recipientIds)) {
            $rows = [];
            foreach ($recipientIds as $recipientId) {
                $rows[] = [
                    'file_id' => $fileId,
                    'client_id' => (int) $recipientId,
                ];
            }
            $this->db->table('file_client_permissions')->insertBatch($rows);
        }

        $this->enqueueMessage($recipientIds, [
            'sender_id' => $clientId,
            'type' => ApiEventTypes::FILE_META,
            'content' => '',
            'file_id' => $fileId,
        ]);
    }

    private function storedResponse(string $publicId, string $fileName, int $fileSize): array
    {
        return [
            'stored' => true,
            'publicId' => $publicId,
            'fileName' => $fileName,
            'fileSize' => $fileSize,
        ];
    }

    public function uploadContext(
        string $sessionName,
        string $token,
        int $contentLength,
    ): array {
        $serverSettings = $this->serverSettingsRow();
        if ($serverSettings === null) {
            return ['error' => ApiErrorMessages::SERVER_NOT_FOUND, 'status' => 500];
        }

        $client = $this->requireClient($sessionName, $token);
        if ($client === null) {
            return ['error' => ApiErrorMessages::INVALID_TOKEN, 'status' => 403];
        }

        $clientId = (int) $client['id'];
        $sessionId = (int) $client['session_id'];
        $this->touchClient($clientId);

        $fileSizeBytesLimit = (int) $serverSettings['file_size_bytes_limit'];
        if ($fileSizeBytesLimit > 0 && $contentLength >= 0 && $contentLength > $fileSizeBytesLimit) {
            return ['error' => ApiErrorMessages::fileExceedsLimit($fileSizeBytesLimit), 'status' => 413];
        }

        $fileCountLimit = (int) $serverSettings['file_count_limit_per_session'];
        if ($fileCountLimit < 0) {
            return ['error' => ApiErrorMessages::FILE_TRANSFER_DISABLED, 'status' => 403];
        }

        $recipientIds = $this->recipientIds($sessionId, $clientId);
        if (empty($recipientIds)) {
            return [
                'stored' => false,
                'clientId' => $clientId,
                'sessionId' => $sessionId,
                'recipientIds' => [],
                'fileSizeBytesLimit' => $fileSizeBytesLimit,
                'fileCountLimit' => $fileCountLimit,
            ];
        }

        if ($fileCountLimit > 0) {
            $existingFiles = (int) $this->db->table('files')->where('session_id', $sessionId)->countAllResults();
            if ($existingFiles >= $fileCountLimit) {
                return ['error' => ApiErrorMessages::FILE_LIMIT_REACHED, 'status' => 403];
            }
        }

        return [
            'clientId' => $clientId,
            'sessionId' => $sessionId,
            'recipientIds' => $recipientIds,
            'fileSizeBytesLimit' => $fileSizeBytesLimit,
            'fileCountLimit' => $fileCountLimit,
        ];
    }
}
