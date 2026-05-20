<?php

namespace App\Models\Api;

final class EventsModel extends ApiModel
{
    public function fetchPendingMessages(int $clientId, int $afterMessageId): array
    {
        $query = $this->db->table('messages m')
            ->select('m.id, m.type, m.sender_id, m.content, m.timestamp, f.public_id AS file_public_id, f.name AS file_name, f.byte_size AS file_size, f.id AS file_id')
            ->join('files f', 'f.id = m.file_id', 'left')
            ->where('m.receiver_id', $clientId)
            ->orderBy('m.id', 'ASC');
        if ($afterMessageId > 0) {
            $query->where('m.id >', $afterMessageId);
        }
        return $query->get()->getResultArray();
    }
}
