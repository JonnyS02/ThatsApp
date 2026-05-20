<?php

use CodeIgniter\Router\RouteCollection;

/**
 * @var RouteCollection $routes
 */
$routes->get('/', 'Stats::index');
$routes->get('stats/data', 'Stats::data');

$routes->group('api', static function (RouteCollection $routes) {
    $routes->post('sessions/(:segment)/handshake', 'Api\\Sessions::handshake/$1');
    $routes->post('sessions/(:segment)/connect', 'Api\\Sessions::connect/$1');
    $routes->post('sessions/(:segment)/disconnect', 'Api\\Sessions::disconnect/$1');
    $routes->post('sessions/(:segment)/heartbeat', 'Api\\Sessions::heartbeat/$1');
    $routes->get('sessions/(:segment)/events', 'Api\\Events::events/$1');
    $routes->post('sessions/(:segment)/messages', 'Api\\Messages::messages/$1');
    $routes->post('sessions/(:segment)/typing', 'Api\\Messages::typing/$1');
    $routes->get('sessions/(:segment)/files/(:segment)', 'Api\\Files::file/$1/$2');
    $routes->match(['post', 'options'], 'sessions/(:segment)/uploads', 'Api\\TusUploads::uploads/$1');
    $routes->match(['head', 'patch', 'options'], 'sessions/(:segment)/uploads/(:segment)', 'Api\\TusUploads::uploads/$1/$2');
});
