#!/usr/bin/env node
/* Note (Amol Ghode) : 
 * Express app for doing peer to peer webrtc call. Signalling happens on WebSocket.
 * This file initialises the https server and websocket.
 * 
 * Limitation : Currently, this supports only peer to peer audio-video calls.
 * And the UI is still rudimentary. 
 */
var app = require('express').express();
var WebSocketServer = require('websocket').server;
var https = require('https');
var fs = require("fs");
var ConnectionManager = require("./connection_manager.js");
var connManager = new ConnectionManager();
var url=require("url");
var fs = require('fs');
// 
var privateKey = fs.readFileSync('./cert/apache.key').toString();
var certificate = fs.readFileSync('./cert/certificate/apache-certificate.crt').toString();
var credentials = {key: privateKey, cert: certificate};

let server = https.createServer(credentials, app);
server.listen(5080, function() {
    console.log((new Date()) + ' Server is listening on port 5080');
});
wsServer = new WebSocketServer({
	httpServer: server,
	autoAcceptConnections: false
});

function originIsAllowed(origin) {
	return true;
}
wsServer.on('request', function(request) {
	console.log('Received new connection from ' + request.origin);
    if (!originIsAllowed(request.origin)) {
        request.reject();
		console.log((new Date()) + ' Connection from origin ' + request.origin + ' rejected.');
		return;
	}
	    
    var connection = request.accept('webrtcconnect', request.origin);
	/* When new connection comes, add it to connection manager.
	 * All the future messages on this connection will be handled by
	 * the connection manager class.
	 */
    connManager.addConnection(connection);
});

/* Serve the client pages and scripts */
app.get('/client/*', (request, response) => {
	let path = url.parse(request.url).pathname;
	console.log('Serving ' + path);
	fs.readFile(__dirname + path, 'utf8', (error, data) => {
		if (error) {
			response.statusCode = 404;
			response.end();
			return;
		} else {
			response.setHeader("Content-Type", "text/html;charset=utf-8");
			response.writeHead(200);
			response.end(data);	
		}
	});
});