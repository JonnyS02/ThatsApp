<?php

namespace App\Models\Api;

use App\Support\ApiErrorMessages;

final class SessionsModel extends ApiModel
{
    public function findOrCreateSession(
        string $sessionName,
        string $accessKeyHash,
        string $nonce,
        bool $chatEncrypted,
        int $sessionLimit,
    ): array
    {
        $this->db->transBegin();
        try {
            $serverSettingsId = $this->serverSettingsId();
            $server = $this->db->query(
                'SELECT id FROM server_settings WHERE id = ? FOR UPDATE',
                [$serverSettingsId],
            )->getRowArray();
            if ($server === null) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::SERVER_NOT_FOUND, 'status' => 500];
            }

            $session = $this->findSession($sessionName);
            if ($session !== null) {
                $this->db->transCommit();
                return ['session' => $session];
            }

            $existingSessions = (int) $this->db->table('sessions')
                ->where('server_settings_id', $serverSettingsId)
                ->countAllResults();
            if ($existingSessions >= $sessionLimit) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::SESSION_LIMIT_REACHED, 'status' => 403];
            }

            $this->db->table('sessions')->insert([
                'name' => $sessionName,
                'access_key' => $accessKeyHash,
                'session_nonce' => $nonce,
                'chat_encrypted' => $chatEncrypted ? 1 : 0,
                'server_settings_id' => $serverSettingsId,
            ]);
            $sessionId = (int) $this->db->insertID();
            if ($sessionId < 1) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_SESSION, 'status' => 500];
            }

            $session = $this->db->table('sessions')->where('id', $sessionId)->get()->getRowArray();
            if ($session === null) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_SESSION, 'status' => 500];
            }

            $this->db->transCommit();
            return ['session' => $session];
        } catch (\Throwable) {
            $this->db->transRollback();
            return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_SESSION, 'status' => 500];
        }
    }

    public function countClientsInSession(int $sessionId): int
    {
        return (int) $this->db->table('clients')->where('session_id', $sessionId)->countAllResults();
    }

    public function clientExists(int $clientId): bool
    {
        if ($clientId < 1) {
            return false;
        }
        return (int) $this->db->table('clients')->where('id', $clientId)->countAllResults() > 0;
    }

    public function deleteClient(int $clientId): void
    {
        if ($clientId < 1) {
            return;
        }
        $this->db->table('clients')->where('id', $clientId)->delete();
    }

    public function deleteSession(int $sessionId): void
    {
        if ($sessionId < 1) {
            return;
        }
        $this->db->table('sessions')->where('id', $sessionId)->delete();
    }

    public function insertClient(int $sessionId, string $token, string $name, string $ipAddress): int
    {
        $this->db->table('clients')->insert([
            'session_id' => $sessionId,
            'token' => $this->hashClientToken($token),
            'name' => $name,
            'ip' => $ipAddress,
        ]);
        return (int) $this->db->insertID();
    }

    public function admitClient(int $sessionId, int $userLimit, string $token, string $name, string $ipAddress): array
    {
        if ($sessionId < 1) {
            return ['error' => ApiErrorMessages::SESSION_NOT_FOUND, 'status' => 404];
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

            $clientCount = $this->countClientsInSession($sessionId);
            if ($userLimit > 0 && $clientCount >= $userLimit) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::SERVER_FULL, 'status' => 403];
            }

            $clientId = $this->insertClient($sessionId, $token, $name, $ipAddress);
            if ($clientId < 1) {
                $this->db->transRollback();
                return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_CLIENT, 'status' => 500];
            }

            $this->db->transCommit();
            return ['clientId' => $clientId];
        } catch (\Throwable) {
            $this->db->transRollback();
            return ['error' => ApiErrorMessages::UNABLE_TO_CREATE_CLIENT, 'status' => 500];
        }
    }

    public function listClients(int $sessionId): array
    {
        return $this->db->table('clients')
            ->select('id, name')
            ->where('session_id', $sessionId)
            ->get()
            ->getResultArray();
    }

    public function unresponsiveClientIds(int $sessionId, int $missedPingLimit): array
    {
        $rows = $this->db->table('clients')
            ->select('id')
            ->where('session_id', $sessionId)
            ->where('missed_pings >=', $missedPingLimit)
            ->get()
            ->getResultArray();

        $ids = [];
        foreach ($rows as $row) {
            $ids[] = (int) $row['id'];
        }
        return $ids;
    }

    public function stalePreStreamClientIds(int $sessionId, int $graceSeconds): array
    {
        if ($sessionId < 1 || $graceSeconds < 1) {
            return [];
        }

        $rows = $this->db->table('clients')
            ->select('id')
            ->where('session_id', $sessionId)
            ->where('events_stream_open', 0)
            ->where(
                'created_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL ' . $graceSeconds . ' SECOND)',
                null,
                false,
            )
            ->get()
            ->getResultArray();

        $ids = [];
        foreach ($rows as $row) {
            $ids[] = (int) $row['id'];
        }
        return $ids;
    }

    public function orphanFileIds(int $sessionId): array
    {
        $rows = $this->db->table('files f')
            ->select('f.id')
            ->join('file_client_permissions p', 'p.file_id = f.id', 'left')
            ->where('f.session_id', $sessionId)
            ->groupBy('f.id')
            ->having('COUNT(p.id) = 0', null, false)
            ->get()
            ->getResultArray();

        $ids = [];
        foreach ($rows as $row) {
            $ids[] = (int) $row['id'];
        }
        return $ids;
    }

    public function deleteFile(int $fileId): void
    {
        if ($fileId < 1) {
            return;
        }
        $this->db->table('files')->where('id', $fileId)->delete();
    }
}
