<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ThatsApp | Server Statistics</title>
    <link rel="icon" href="<?= esc(base_url('icon.ico')) ?>" type="image/x-icon">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css">
    <link rel="stylesheet" href="<?= esc(base_url('style.css')) ?>">
</head>
<body class="min-vh-100">
    <div class="position-fixed top-0 bottom-0 start-0 end-0 pe-none z-0">
        <div class="stats-orb stats-orb--left position-absolute rounded-circle"></div>
        <div class="stats-orb stats-orb--right position-absolute rounded-circle"></div>
    </div>

    <div class="container position-relative z-1 py-3 py-xl-4 stats-container">
        <header class="mb-4">
            <nav class="rounded-5 border bg-white px-3 px-md-4 py-3 shadow-sm">
                <div class="row g-3 align-items-center">
                    <div class="col-12 col-sm-auto">
                        <div class="d-flex align-items-center gap-3 text-nowrap">
                            <img class="stats-brand-icon" src="<?= esc(base_url('icon.ico')) ?>" alt="ThatsApp">
                            <div>
                                <p class="mb-0 fw-semibold text-dark">ThatsApp</p>
                                <p class="mb-0 small text-muted">Server Statistics</p>
                            </div>
                        </div>
                    </div>
                    <div class="col-12 col-sm">
                        <div class="d-flex flex-column flex-sm-row flex-md-wrap flex-xl-nowrap align-items-stretch align-items-sm-center justify-content-sm-end gap-2 text-muted">
                            <span class="d-inline-flex align-items-center justify-content-center justify-content-sm-start gap-2 rounded-pill border bg-white px-3 py-2 shadow-sm text-nowrap">
                                <i class="fa-solid fa-rotate text-primary"></i>
                                Next refresh in
                                <span id="refresh-countdown" class="fw-semibold text-dark">--</span>
                            </span>
                            <span class="d-inline-flex align-items-center justify-content-center justify-content-sm-start gap-2 rounded-pill border bg-white px-3 py-2 shadow-sm text-nowrap">
                                Last updated
                                <span id="last-updated" class="fw-semibold text-dark">--</span>
                            </span>
                        </div>
                    </div>
                </div>
            </nav>
        </header>

        <div id="stats-error" class="alert alert-warning d-none" role="alert">
            Unable to refresh statistics. Retrying...
        </div>

        <section id="stats-overview" class="row g-4 mb-4">
            <?php foreach ([
                [
                    'iconBg' => 'icon-bg-lighter',
                    'iconClass' => 'fa-solid fa-layer-group',
                    'valueId' => 'stat-sessions',
                    'value' => $stats['sessions'],
                    'label' => 'Active sessions',
                ],
                [
                    'iconBg' => 'icon-bg-green',
                    'iconClass' => 'fa-solid fa-users',
                    'valueId' => 'stat-users',
                    'value' => $stats['users'],
                    'label' => 'Connected users',
                ],
                [
                    'iconBg' => 'icon-bg-rose',
                    'iconClass' => 'fa-solid fa-database',
                    'valueId' => 'stat-storage',
                    'value' => '--',
                    'label' => 'Storage used',
                ],
            ] as $statCard) : ?>
                <div class="col-md-4">
                    <div class="card border shadow-sm rounded-4 h-100 bg-white">
                        <div class="card-body p-4">
                            <div class="d-inline-flex align-items-center justify-content-center rounded-4 icon-square <?= esc($statCard['iconBg']) ?>">
                                <i class="<?= esc($statCard['iconClass']) ?>"></i>
                            </div>
                            <div class="fs-2 fw-semibold text-dark mt-2" id="<?= esc($statCard['valueId']) ?>"><?= esc($statCard['value']) ?></div>
                            <div class="text-uppercase small text-muted tracking-compact"><?= esc($statCard['label']) ?></div>
                        </div>
                    </div>
                </div>
            <?php endforeach; ?>
        </section>

        <section id="stats-limits" class="card border shadow-sm rounded-4 bg-white">
            <div class="card-body p-4">
                <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
                    <div>
                        <h2 class="h5 mb-1">Server limits</h2>
                        <p class="text-muted small mb-0">
                            Limits are loaded from active server_settings row
                            <span id="server-settings-id" class="fw-semibold">#<?= esc($stats['serverSettingsId']) ?></span>.
                        </p>
                    </div>
                </div>
                <div class="row g-3">
                    <?php foreach ([
                        [
                            'label' => 'Session limit',
                            'valueId' => 'limit-session',
                            'value' => $stats['limits']['sessionLimit'],
                        ],
                        [
                            'label' => 'User limit per session',
                            'valueId' => 'limit-user',
                            'value' => $stats['limits']['userLimit'],
                        ],
                        [
                            'label' => 'Message character limit',
                            'valueId' => 'limit-message',
                            'value' => $stats['limits']['messageCharacterLimit'],
                        ],
                        [
                            'label' => 'File count limit per session',
                            'valueId' => 'limit-file-count',
                            'value' => $stats['limits']['fileCountLimit'],
                        ],
                        [
                            'label' => 'File size limit',
                            'valueId' => 'limit-file-size',
                            'value' => '--',
                        ],
                        [
                            'label' => 'Missed ping limit',
                            'valueId' => 'limit-missed-pings',
                            'value' => $stats['limits']['clientMissedPingLimit'],
                        ],
                        [
                            'label' => 'SSE ping interval',
                            'valueId' => 'limit-ping-interval',
                            'value' => $stats['limits']['eventsPingIntervalSeconds'],
                        ],
                        [
                            'label' => 'Event loop sleep',
                            'valueId' => 'limit-poll-sleep',
                            'value' => $stats['limits']['eventsPollSleepMicroseconds'],
                        ],
                    ] as $limitCard) : ?>
                        <div class="col-md-6 col-lg-4">
                            <div class="border rounded-4 px-3 py-2 h-100 bg-white shadow-sm">
                                <div class="text-uppercase small text-muted tracking-compact"><?= esc($limitCard['label']) ?></div>
                                <div class="fs-6 fw-semibold text-dark mt-1" id="<?= esc($limitCard['valueId']) ?>"><?= esc($limitCard['value']) ?></div>
                            </div>
                        </div>
                    <?php endforeach; ?>
                </div>
            </div>
        </section>
    </div>

    <script src="https://code.jquery.com/jquery-3.7.1.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
    <script>
        $(function () {
            const statsEndpoint = <?= json_encode($statsEndpoint, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?>;
            const pollIntervalSeconds = <?= $pollIntervalSeconds ?>;
            const initialStats = <?= json_encode($stats, JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT) ?>;
            let secondsRemaining = pollIntervalSeconds;

            const $statsError = $('#stats-error');
            const $refreshCountdown = $('#refresh-countdown');
            const $lastUpdated = $('#last-updated');
            const $serverSettingsId = $('#server-settings-id');
            const $statSessions = $('#stat-sessions');
            const $statUsers = $('#stat-users');
            const $statStorage = $('#stat-storage');
            const $limitUser = $('#limit-user');
            const $limitSession = $('#limit-session');
            const $limitFileSize = $('#limit-file-size');
            const $limitFileCount = $('#limit-file-count');
            const $limitMessage = $('#limit-message');
            const $limitMissedPings = $('#limit-missed-pings');
            const $limitPingInterval = $('#limit-ping-interval');
            const $limitPollSleep = $('#limit-poll-sleep');

            function formatBytes(bytes) {
                if (bytes <= 0) {
                    return '0 B';
                }
                const units = ['B', 'KB', 'MB', 'GB', 'TB'];
                const base = 1024;
                let size = bytes;
                let unitIndex = 0;
                while (size >= base && unitIndex < units.length - 1) {
                    size /= base;
                    unitIndex += 1;
                }
                const value = unitIndex === 0 ? Math.round(size) : size.toFixed(1);
                return value + ' ' + units[unitIndex];
            }

            function formatLimitValue(value, suffix = '') {
                if (value < 0) {
                    return 'Disabled';
                }
                if (value === 0) {
                    return 'Unlimited';
                }
                return value.toString() + suffix;
            }

            function formatMicroseconds(value) {
                if (value >= 1000 * 1000) {
                    return (value / (1000 * 1000)).toFixed(value % (1000 * 1000) === 0 ? 0 : 2) + ' s';
                }
                if (value >= 1000) {
                    return (value / 1000).toFixed(value % 1000 === 0 ? 0 : 1) + ' ms';
                }
                return value + ' us';
            }

            function applyStats(data) {
                const limits = data.limits;

                $statSessions.text(data.sessions);
                $statUsers.text(data.users);
                $statStorage.text(formatBytes(Number(data.fileBytes)));
                $serverSettingsId.text('#' + data.serverSettingsId);

                $limitUser.text(formatLimitValue(Number(limits.userLimit)));
                $limitSession.text(limits.sessionLimit);
                const fileSizeBytesLimit = Number(limits.fileSizeBytesLimit);
                $limitFileSize.text(fileSizeBytesLimit === 0 ? 'Unlimited' : formatBytes(fileSizeBytesLimit));
                $limitFileCount.text(formatLimitValue(Number(limits.fileCountLimit)));
                $limitMessage.text(formatLimitValue(Number(limits.messageCharacterLimit), ' chars'));
                $limitMissedPings.text(limits.clientMissedPingLimit);
                $limitPingInterval.text(limits.eventsPingIntervalSeconds + ' s');
                $limitPollSleep.text(formatMicroseconds(Number(limits.eventsPollSleepMicroseconds)));

                $lastUpdated.text(new Date().toLocaleTimeString());
            }

            function refreshStats() {
                return $.getJSON(statsEndpoint)
                    .done(function (data) {
                        $statsError.addClass('d-none');
                        applyStats(data);
                    })
                    .fail(function (xhr) {
                        if (xhr.status === 404) {
                            location.reload();
                            return;
                        }
                        $statsError.removeClass('d-none');
                    });
            }

            function updateCountdown() {
                $refreshCountdown.text(secondsRemaining + 's');
                if (secondsRemaining === 0) {
                    secondsRemaining = pollIntervalSeconds;
                    refreshStats();
                    return;
                }
                secondsRemaining -= 1;
            }

            applyStats(initialStats);
            refreshStats();
            secondsRemaining = pollIntervalSeconds;
            updateCountdown();
            setInterval(updateCountdown, 1000);
        });
    </script>
</body>
</html>
