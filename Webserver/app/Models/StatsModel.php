<?php

namespace App\Models;

use App\Models\Api\ApiModel;

final class StatsModel extends ApiModel
{
    public function stats(array $serverSettings): array
    {
        $serverSettingsId = $this->serverSettingsId();
        $sessionCount = (int) $this->db->table('sessions')->where('server_settings_id', $serverSettingsId)->countAllResults();

        $userCount = (int) $this->db->table('clients c')
            ->join('sessions s', 's.id = c.session_id')
            ->where('s.server_settings_id', $serverSettingsId)
            ->countAllResults();

        $row = $this->db->table('files f')
            ->selectSum('f.byte_size', 'total_bytes')
            ->join('sessions s', 's.id = f.session_id')
            ->where('s.server_settings_id', $serverSettingsId)
            ->get()
            ->getRowArray();
        $totalBytes = $row === null ? 0 : (int) $row['total_bytes'];

        return [
            'serverSettingsId' => $serverSettingsId,
            'sessions' => $sessionCount,
            'users' => $userCount,
            'fileBytes' => $totalBytes,
            'limits' => [
                'userLimit' => (int) $serverSettings['user_limit_per_session'],
                'sessionLimit' => (int) $serverSettings['session_limit'],
                'fileSizeBytesLimit' => (int) $serverSettings['file_size_bytes_limit'],
                'fileCountLimit' => (int) $serverSettings['file_count_limit_per_session'],
                'messageCharacterLimit' => (int) $serverSettings['message_character_limit'],
                'clientMissedPingLimit' => (int) $serverSettings['client_missed_ping_limit'],
                'eventsPingIntervalSeconds' => (int) $serverSettings['events_ping_interval_seconds'],
                'eventsPollSleepMicroseconds' => (int) $serverSettings['events_poll_sleep_microseconds'],
            ],
        ];
    }
}
