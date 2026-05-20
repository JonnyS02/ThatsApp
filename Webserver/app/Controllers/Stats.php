<?php

namespace App\Controllers;

use App\Models\StatsModel;
use App\Support\ApiErrorMessages;
use CodeIgniter\Exceptions\PageNotFoundException;
use CodeIgniter\HTTP\ResponseInterface;

final class Stats extends BaseController
{
    private const POLL_INTERVAL_SECONDS = 10;

    public function index(): string
    {
        $model = new StatsModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null || !$this->statsEnabled($serverSettings)) {
            throw PageNotFoundException::forPageNotFound();
        }

        helper('url');

        $stats = $model->stats($serverSettings);
        return view('stats', [
            'stats' => $stats,
            'pollIntervalSeconds' => self::POLL_INTERVAL_SECONDS,
            'statsEndpoint' => site_url('stats/data'),
        ]);
    }

    public function data(): ResponseInterface
    {
        $model = new StatsModel();
        $serverSettings = $model->serverSettingsRow();
        if ($serverSettings === null) {
            return $this->response->setStatusCode(500)->setJSON([
                'error' => ApiErrorMessages::SERVER_NOT_FOUND,
                'status' => 500,
            ]);
        }
        if (!$this->statsEnabled($serverSettings)) {
            throw PageNotFoundException::forPageNotFound();
        }

        return $this->response->setJSON($model->stats($serverSettings));
    }

    private function statsEnabled(array $serverSettings): bool
    {
        $flag = $serverSettings['public_stats_enabled'] ?? 1;
        return (int) $flag === 1;
    }
}
