/* Note(Amol Ghode): This class is responsible for maintaining a
 * connection with webSocket client and orchestrating the call.
 * This class works as a hybrid of SIP registrar and B2B userAgent.
 * This negotiation does not handle a multiparty conferencing, nevertheless
 * that should be trivial to implement.
 */
var util = require('util')
var constants = require('./constants.js')
const { v4: uuidv4 } = require('uuid');
module.exports = function () {
    this.connections = [];
    /* addConnection : Adds a new connection.
     * Input : New Websocket client connection.
     * Output : None.
     * Description : This function accepts a new WebSocket and adds it in
     * the connection connection Array by wrapping up under connectionInfo structure.
     * Important thing to note here is the
     * usage of UUID. Once the connection is succesful, client sends REGISTER
     * message. For the clients behind the firewall, the remote end
     * of the client will always be a single IP address. So to distinguish between
     * 2 different clients REGISTERING from same network, uuid is used. When the socket
     * gets added in the pool, this UUID is added. All the subsequent messages
     * coming on this socket will be related to a particular socket instance based on
     * this UUID.
     */
    this.addConnection = (connection) => {
        connection.uuid = uuidv4();
        let connectionInfo = {
            remote: connection.remoteAddress,
            peer_id: "unknown",
            connection: connection
        }
        this.connections.push(connectionInfo);
        /* Bind connection methods. */
        connection.on("message", this.onMessage.bind(this, connection));
        connection.on("close", this.onClose.bind(this, connection));
    }
    /* onMessage: Handles a message from WebRTC client.
     * Input : connection and a webRTC Message.
     * Output : None.
     * Description : This function is a responsible for dispatching the message
     * to the handler function based on its method signature.
     */
    this.onMessage = (connection, message) => {
        let protoMessage =  JSON.parse(message.utf8Data);
        console.log("message = ", protoMessage.method);
        switch(protoMessage.method) {
            case constants.MethodRequest.REGISTER :
                this.handleRegisterMessage(connection, protoMessage);
                break;
            case constants.MethodRequest.INVITE_PEER:
            case constants.MethodResponse.INVITE_PEER_RESPONSE:
                this.handlePeerInviteMessage(connection, protoMessage);
                break;
            case constants.MethodRequest.ICE:
                this.handleIce(connection, protoMessage);
                break;
            case constants.MethodRequest.DISCONNECT_CALL:
                this.handleDisconnect(connection, protoMessage);
                break;

        }
    }
    /* onClose: Handles closing of a connection. This is called when peer cloeses
     * the webRTC connection.
     * Input : connection.
     * Output : None.
     * Description : When connection is closed, this function gets called. This function then deletes
     * the connection from local connection Array..
     */
    this.onClose = (connection) => {
        let connectionToDelete = this.getConnectionInfo(connection);
        if (connectionToDelete !== null) {
            console.log("connection is getting deleted. uuid = " + connectionToDelete.connection.uuid);
            this.connections.splice(this.connections.indexOf(connectionToDelete), 1);
        }
        console.log("Remaining  = " + util.inspect(this.connections));
    }
    /* getConnectionInfo: Returns a connection info object associated with this connection.
     * Input : connection.
     * Output : connectionInfo.
     * Description : This function uniquely identifies the connection and returns the connection Inf.
     * The connection Info represents a basic WebRTC client connection, where as connectionInfo.connection
     * represents raw Websocket connection. This functionality is used for associating socket with
     * WebRTC entity, during the "REGISTER call."
     */
    this.getConnectionInfo = (connection) => {
        for  (let i = 0; i < this.connections.length; i++) {
            if (this.connections[i].connection.uuid === connection.uuid) {
                return this.connections[i];
            }
        }
        return null;
    }
    /* getConnectionInfoByPeerId: Returns a connection info object identified by peerId
     * Input : peerId.
     * Output : connectionInfo.
     * Description : This function uniquely identifies the connection and returns the connectionInfo based on
     * peerId. This function is used for finding the other end of the call from the connection Pool.
     */
    this.getConnectionInfoByPeerId = (peerId) => {
        for  (let i = 0; i < this.connections.length; i++) {
            if (this.connections[i].peer_id === peerId) {
                return this.connections[i];
            }
        }
        return null;
    }
    /* handleRegisterMessage: Registers a WebRTC client.
     * Input : Connection and REGISTER message.
     * Output : None..
     * Description : Client calls REGISTER message. Register message contains peerId,
     * this peerId is the id of the peer that sends a register message. This function associates
     * a webSocket with peerId and thats how a complete identity of WebRTC entity is formed.
     * Now onwards, all the messasges coming on this socket are treated as if they are coming from
     * a webRTC entity.
     * If the registration is successful, this function returns a REGISTER_RESPONSE response with
     * status as success, else failure.
     */
    this.handleRegisterMessage = (connection, message) => {
        let connectionInfo = this.getConnectionInfo(connection);
        message.method = constants.MethodResponse.REGISTER_RESPONSE;
        if (connectionInfo !== null) {
            connectionInfo.peer_id = message.peer_id;
            message.status = "SUCCESS";
        } else {
            message.status = "FAILURE";
        }
        console.log("Peer id = " + connectionInfo.peer_id);
        console.log("connection UUID  = " + connectionInfo.connection.uuid);
        connection.sendUTF(JSON.stringify(message));
    }
    /* handlePeerInviteMessage: Registers a WebRTC client.
     * Input : Connection and
     * INVITE_PEER message with offer SDP or its response with answer SDP.
     * Output : None..
     * Description :
     *
     * This function is responsible for connecting a WebRTC UAC (WUAC Sender of the message)
     * to another WebRTC endpoint(WUAS). WUAC sends the message to WUAS. WUAS is either registered or it is not.
     * (message contains far-end-peer-id based on which this function finds out whether
     * the far_end is registered or not.) In case it is not registered, we return an error to
     * WUAC.
     *
     * If the WUAS is found to be registered, it sends the request to WUAS by locating it in the
     * connection map, based on farend_peer_id. The request contains offer SDP.
     * Far end processes the invite request and sends the answer SDP in INVITE_PEER_RESPONSE.
     *
     * Upon receiving INVITE_PEER_RESPONSE with answer SDP, this function looks in to the map again
     * this time based on peer_id and send the response back.
     */
    this.handlePeerInviteMessage = (connection, message) => {
        let messageReceiverEndpoint = null;
        if (message.method == constants.MethodRequest.INVITE_PEER) {
            console.log("Invite Peer");
            messageReceiverEndpoint = this.getConnectionInfoByPeerId(message.farend_peer_id);
        } else if (message.method ==  constants.MethodResponse.INVITE_PEER_RESPONSE) {
            console.log("Invite Peer response");
            messageReceiverEndpoint = this.getConnectionInfoByPeerId(message.peer_id);
        }
        if (messageReceiverEndpoint) {
            console.log("Found farend connection uuid = " + messageReceiverEndpoint.connection.uuid);
            messageReceiverEndpoint.connection.sendUTF(JSON.stringify(message));
        } else {
            message.method = constants.MethodResponse.INVITE_PEER_RESPONSE;
            message.status = "FAREND_NOT_FOUND";
            connection.sendUTF(JSON.stringify(message));
        }
    }
    /* handleIce: Forwards ICE message to far End.
     * Input : Connection and ICE candidate.
     * Output : None.
     * Description :
     * This function locates far end candidate and send the ICE candidate to the
     * far end. If the far end candidate is not yet registered, this function returns error
     * FAREND_NOT_FOUND
     */
    this.handleIce = (connection, message) => {
        let messageReceiverEndpoint = this.getConnectionInfoByPeerId(message.farend_peer_id);
         if (messageReceiverEndpoint) {
            console.log("Found farend connection uuid = " + messageReceiverEndpoint.connection.uuid);
            messageReceiverEndpoint.connection.sendUTF(JSON.stringify(message));
        } else {
            message.method = constants.MethodResponse.ICE_RESPONSE;
            message.status = "FAREND_NOT_FOUND";
            connection.sendUTF(JSON.stringify(message));
        }
    }
    /* handleDisconnect: Conveys the other endpoint that the connection is closed
     * Input : Connection and disconnect message.
     * Output : None.
     * Description :
     * This function sends disconnect message to far end point. Returns error to the called
     * if the far end is not found.
     */
    this.handleDisconnect = (connection, message) => {
        let messageReceiverEndpoint = this.getConnectionInfoByPeerId(message.farend_peer_id);
         if (messageReceiverEndpoint) {
            console.log("Found farend connection uuid = " + messageReceiverEndpoint.connection.uuid);
            messageReceiverEndpoint.connection.sendUTF(JSON.stringify(message));
        } else {
            message.method = constants.MethodResponse.DISCONNECT_CALL_RESPONSE;
            message.status = "FAREND_NOT_FOUND";
            connection.sendUTF(JSON.stringify(message));
        }
    }
}
