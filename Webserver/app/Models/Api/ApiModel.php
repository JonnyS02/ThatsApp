<?php

namespace App\Models\Api;

use CodeIgniter\Database\BaseConnection;
use Random\RandomException;

class ApiModel
{
    private const DEFAULT_SERVER_SETTINGS_ID = 1;

    protected BaseConnection $db;
    private int $serverSettingsId;

    public function __construct(?BaseConnection $db = null)
    {
        $this->db = $db ?? db_connect();
        $this->serverSettingsId = $this->resolveServerSettingsId();
    }

    public function serverSettingsRow(): ?array
    {
        return $this->db->table('server_settings')->where('id', $this->serverSettingsId)->get()->getRowArray();
    }

    public function findSession(string $sessionName): ?array
    {
        return $this->db->table('sessions')->where([
            'server_settings_id' => $this->serverSettingsId,
            'name' => $sessionName,
        ])->get()->getRowArray();
    }

    public function findClientBySessionToken(int $sessionId, string $token): ?array
    {
        if ($token === '' || $sessionId < 1) {
            return null;
        }

        return $this->db->table('clients')->where([
            'session_id' => $sessionId,
            'token' => $this->hashClientToken($token),
        ])->get()->getRowArray();
    }

    public function touchClient(int $clientId): void
    {
        if ($clientId < 1) {
            return;
        }
        $this->db->table('clients')->where('id', $clientId)->update([
            'missed_pings' => 0,
        ]);
    }

    public function incrementMissedPings(int $clientId): int
    {
        if ($clientId < 1) {
            return -1;
        }

        $this->db->table('clients')
            ->where('id', $clientId)
            ->set('missed_pings', 'missed_pings + 1', false)
            ->update();

        if ($this->db->affectedRows() < 1) {
            return -1;
        }

        $row = $this->db->table('clients')->select('missed_pings')->where('id', $clientId)->get()->getRowArray();
        return $row === null ? -1 : (int) $row['missed_pings'];
    }

    public function acknowledgeMessages(int $clientId, int $lastEventId): void
    {
        if ($clientId < 1 || $lastEventId < 1) {
            return;
        }
        $this->db->table('messages')
            ->where('receiver_id', $clientId)
            ->where('id <=', $lastEventId)
            ->delete();
    }

    public function claimEventStream(int $clientId): bool
    {
        if ($clientId < 1) {
            return false;
        }
        $this->db->table('clients')
            ->where('id', $clientId)
            ->where('events_stream_open', 0)
            ->update([
                'events_stream_open' => 1,
            ]);
        return $this->db->affectedRows() > 0;
    }

    protected function requireClient(string $sessionName, string $token): ?array
    {
        if ($token === '') {
            return null;
        }
        $session = $this->findSession($sessionName);
        if ($session === null) {
            return null;
        }
        return $this->findClientBySessionToken((int) $session['id'], $token);
    }

    public function recipientIds(int $sessionId, int $excludeClientId): array
    {
        $query = $this->db->table('clients')
            ->select('id')
            ->where('session_id', $sessionId);
        if ($excludeClientId > 0) {
            $query->where('id !=', $excludeClientId);
        }
        $rows = $query->get()->getResultArray();
        $ids = [];
        foreach ($rows as $row) {
            $ids[] = (int) $row['id'];
        }
        return $ids;
    }

    public function enqueueMessage(array $recipientIds, array $row): void
    {
        if (empty($recipientIds)) {
            return;
        }
        $rows = [];
        foreach ($recipientIds as $recipientId) {
            $row['receiver_id'] = (int) $recipientId;
            $rows[] = $row;
        }
        $this->db->table('messages')->insertBatch($rows);
    }

    protected function serverSettingsId(): int
    {
        return $this->serverSettingsId;
    }

    protected function hashClientToken(string $token): string
    {
        return hash('sha256', $token);
    }

    private function resolveServerSettingsId(): int
    {
        $id = (int) env('thatsapp.serverId', self::DEFAULT_SERVER_SETTINGS_ID);
        return $id > 0 ? $id : self::DEFAULT_SERVER_SETTINGS_ID;
    }

    /**
     * @throws RandomException
     */
    protected function uuidV4(): string
    {
        $data = random_bytes(16);
        $data[6] = chr((ord($data[6]) & 0x0f) | 0x40);
        $data[8] = chr((ord($data[8]) & 0x3f) | 0x80);
        $hex = bin2hex($data);
        return substr($hex, 0, 8) . '-' .
            substr($hex, 8, 4) . '-' .
            substr($hex, 12, 4) . '-' .
            substr($hex, 16, 4) . '-' .
            substr($hex, 20, 12);
    }
}
