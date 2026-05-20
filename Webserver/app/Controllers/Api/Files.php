<?php

namespace App\Controllers\Api;

use App\Models\Api\FilesModel;
use CodeIgniter\HTTP\DownloadResponse;
use CodeIgniter\HTTP\ResponseInterface;

final class Files extends ApiBaseController
{
    public function file(string $sessionName, string $publicId): ResponseInterface|DownloadResponse
    {
        $sessionName = $this->requireSessionName($sessionName);
        if ($sessionName instanceof ResponseInterface) {
            return $sessionName;
        }

        $model = new FilesModel();
        $context = $this->requireSessionClient($model, $sessionName);
        if ($context instanceof ResponseInterface) {
            return $context;
        }
        $result = $this->unwrapResult($model->download(
            (int) $context['session']['id'],
            (int) $context['client']['id'],
            $publicId,
        ));
        if ($result instanceof ResponseInterface) {
            return $result;
        }
        return $this->response->download((string) $result['path'], null)->setFileName((string) $result['name']);
    }
}
