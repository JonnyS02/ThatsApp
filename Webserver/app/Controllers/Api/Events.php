<?php

namespace App\Controllers\Api;

use App\Models\Api\EventsModel;
use App\Models\Api\SessionsModel;
use App\Support\ApiErrorMessages;
use CodeIgniter\HTTP\ResponseInterface;

final class Events extends ApiBaseController
{
    use SessionLifecycleTrait;

    public function events(string $sessionName)
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $model = new EventsModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null) {
            return $this->jsonError(ApiErrorMessages::SERVER_NOT_FOUND, 500);
        }
        $missedPingLimit = (int) $serverSettings['client_missed_ping_limit'];
        $pingIntervalSeconds = (int) $serverSettings['events_ping_interval_seconds'];
        $pollSleepMicroseconds = (int) $serverSettings['events_poll_sleep_microseconds'];

        $context = $this->requireSessionClient($model, $sessionName, ApiErrorMessages::SESSION_NOT_FOUND, 404, true);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $session = $context['session'];
        $client = $context['client'];

        $sessionId = (int) $session['id'];
        $clientId = (int) $client['id'];
        $lastEventId = $this->lastEventIdHeader('Last-Event-ID');
        $sessionModel = new SessionsModel();
        $streamClaimed = false;
        $response = null;
        try {
            if (!$model->claimEventStream($clientId)) {
                $response = $this->jsonError(ApiErrorMessages::EVENT_STREAM_ALREADY_CONNECTED, 409);
            } else {
                $streamClaimed = true;

                $model->touchClient($clientId);
                $model->acknowledgeMessages($clientId, $lastEventId);

                $this->response
                    ->setHeader('Content-Type', 'text/event-stream')
                    ->setHeader('Cache-Control', 'no-cache')
                    ->setHeader('Connection', 'keep-alive')
                    ->setHeader('X-Accel-Buffering', 'no')
                    ->sendHeaders();

                if (ENVIRONMENT !== 'testing') {
                    while (ob_get_level() > 0) {
                        ob_end_flush();
                    }
                }
                ob_implicit_flush();
                set_time_limit(0);

                echo ": connected\n\n";
                flush();

                $lastPing = time();
                while (!connection_aborted()) {
                    $rows = $model->fetchPendingMessages($clientId, $lastEventId);

                    if (!empty($rows)) {
                        foreach ($rows as $row) {
                            $id = (int) $row['id'];
                            $data = 'type=' . rawurlencode((string) $row['type'])
                                . '&senderId=' . (int) $row['sender_id']
                                . '&message=' . rawurlencode((string) $row['content'])
                                . '&timestamp=' . rawurlencode((string) $row['timestamp']);
                            if ($row['file_public_id'] !== null && $row['file_public_id'] !== '') {
                                $data .= '&filePublicId=' . rawurlencode((string) $row['file_public_id'])
                                    . '&fileName=' . rawurlencode((string) $row['file_name'])
                                    . '&fileSize=' . (int) $row['file_size']
                                    . '&downloadPath=' . rawurlencode('/api/sessions/' . rawurlencode($sessionName) . '/files/' . rawurlencode((string) $row['file_public_id']));
                            }
                            echo 'id: ' . $id . "\n";
                            echo 'event: ' . $row['type'] . "\n";
                            echo 'data: ' . $data . "\n\n";
                            flush();
                            $lastEventId = $id;
                        }
                    }

                    $now = time();
                    if ($now - $lastPing >= $pingIntervalSeconds) {
                        $missedPings = $model->incrementMissedPings($clientId);
                        echo ": ping\n\n";
                        flush();
                        $lastPing = $now;

                        if ($missedPings < 0 || $missedPings >= $missedPingLimit) {
                            break;
                        }
                    }
                    usleep($pollSleepMicroseconds);
                }
            }
        } finally {
            if ($streamClaimed) {
                $this->finalizeDisconnect($sessionModel, $sessionId, $clientId);
            }
        }
        return $response;
    }
}
