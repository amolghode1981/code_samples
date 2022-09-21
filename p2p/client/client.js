/* Note (Amol Ghode) : This class acts as UAC as well as UAS. Ideally this should
 * be in to separate classes, nevertheless, this is just to demonstrate a 
 * basic webRTC peer to peer call establishment. So the design is not the optimal one.
 */
const ClientState  ={
  INIT: "init",
  REGISTERED: "registered",
  OFFER_SENT: "offer_sent",
  OFFER_RECEIVED: "offer_received",
  ANSWER_SENT:  "answer_sent",
  ANSWER_RECEIVED:"answer_received",
  IN_CALL: "in_call",
  ERROR: "error"
}
//https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/enumerateDevices
//https://gist.github.com/zziuni/3741933
class WebRTCClient {
  constructor(server) {
    this.socketEndpoint = server;
    this.clientState = ClientState.INIT;
    this.constraints = {audio: true, video: true};
    this.configuration = {iceServers: [
      {urls: 'stun:stun.l.google.com:19302'},
      {urls: 'stun:stun1.l.google.com:19302'},
      {urls: 'stun:stun.ekiga.net'}
    ]};
    this.localStream = null;
    this.role = "unknown"
    this.pendingOffer = null;
  }

  /* connect() : Connects to the Remote websocket.
   * description: Creates a connection with remote webRTC server.
   * All the protocol messages are exchanged via this socket.
   * input : none.
   * output : none.
   * exception: If the connection fails, throws an error
   */
  async connect() {
    return new Promise((resolve, reject) => {
      this.wsClient = new WebSocket(this.socketEndpoint, 'webrtcconnect');
      this.wsClient.onmessage = this.onMessage.bind(this);
      this.wsClient.onopen = () => {
        console.log("Opened");
        resolve();
      };
      this.wsClient.onerror = (err) => {
        console.error("socket connection error : ", err);
        reject(err);
      }
    });
  }

  /* initPeerConnection() : Initialises the peer connection and sets the necessary event handlers
   * description: This function creates a webRTC peer connection with configuration (STUN and TURN configuration)
   * This function sets necessary callbacks for ice candidates, track addion.
   * input : none.
   * output : none.
   * exception: none
   */
  async initPeerConnection() {
    this.connection = new RTCPeerConnection(this.configuration);
    //this.connection.onnegotiationneeded = this.onNegotiationNeeded.bind(this);
    this.connection.onicecandidate = this.onIceCandidate.bind(this);
    this.connection.ontrack = this.onRemoteTrack.bind(this);
    this.devices = await navigator.mediaDevices.enumerateDevices();
  }

  /* registerEndpoint() : Registers endpoint to receive the call.
   * description: 
   * input : endpoint id.
   * output : none.
   * exception: none
   */  
  registerEndpoint(endpointId) {
    this.endPoint = endpointId;
    let endpointInfo = {
      peer_id:this.endPoint,
      method:Constants.MethodRequest.REGISTER
    };
    console.log("Sending Register message");
    this.wsClient.send(JSON.stringify(endpointInfo));
  }
  /* registerEndpoint() : Calls the registered remote peer.
   * description: This function calls the registered remote peer. Calling remote peer is possible only
   * if this peer is registered. This function also initialises the peer connection. Once the peer
   * connection is initialised, it requests for the local media stream and adds the tracks to 
   * peer connection. It initiates offer creation process by calling createOffer
   * input : remote endpoint id.
   * output : none.
   * exception: none
   */  
  async callPeer(remotePeer) {
    if (this.clientState != ClientState.REGISTERED) {
      alert("Register the client first");
      return;
    }
    this.initPeerConnection();
    try {
      const stream =
        await navigator.mediaDevices.getUserMedia(this.constraints);
      this.localStream = stream;
      stream.getTracks().forEach((track) =>
        this.connection.addTrack(track, stream));
      this.farEndPoint = remotePeer;
      document.getElementById("nearEnd").srcObject = stream;
      this.role = "UAC";
      await this.connection.createOffer();
      await this.connection.setLocalDescription(this.connection.localDescription);
    } catch (err) {
      console.error(err);
    }
  }

  /* endCall() : Ends the call
   * description: stops the local stream tracks. and sends message to the other party 
   * for disconnecting call.
   * input : none
   * output : none.
   * exception: none
   */  
  async endCall () {
    this.localStream.getTracks().forEach((track) =>
      track.stop());
    let offer = {
      method: Constants.MethodRequest.DISCONNECT_CALL,
      peer_id:this.endPoint,
      farend_peer_id:this.farEndPoint
    }
    this.wsClient.send(JSON.stringify(offer));
    this.clientState = ClientState.OFFER_SENT;
  }

  /* onMessage() : Callback when websocket receives a message.
   * description: 
   * input : event containing received message
   * output : none.
   * exception: none
   */
  onMessage(event) {
    let message = JSON.parse(event.data);
    this.parseMessage(message);
  }

  onClose() {
    console.log("onClose ");
  }

  /* sendOffer() : Sends offer to far end. 
   * description: Sends the offer to far end by reading the local description.
   * The client that sends offer is UAC (User Agent Client). This can either be called
   * after calling createOffer on peer connection or after ice gathering is complete.
   * In this case, we are calling this after the client finishes ice gathering.
   * input : none
   * output : none.
   * exception: none
   */  
  async sendOffer() {
    try {
      console.log("Offer = " + JSON.stringify(this.connection.localDescription));
      let offer = {
        method: Constants.MethodRequest.INVITE_PEER,
        peer_id:this.endPoint,
        farend_peer_id:this.farEndPoint,
        offer:this.connection.localDescription
      }
      this.wsClient.send(JSON.stringify(offer));
      this.clientState = ClientState.OFFER_SENT;
    } catch (err) {
      console.error(err);
    }
  }

  /* sendAnswer() : Sends answer to far end. 
   * description: Sends the answer to far end by reading the local description.
   * The client that sends answer is UAS (User Agent Server). This can either be called
   * after calling createAnswer on peer connection or after ice gathering is complete.
   * In this case, we are calling this after the client finishes ice gathering.
   * input : none
   * output : none.
   * exception: none
   */  
  async sendAnswer () {
    console.log("Answer = " + JSON.stringify(this.connection.localDescription));
    this.pendingOffer.method = Constants.MethodResponse.INVITE_PEER_RESPONSE;
    this.pendingOffer.answer = this.connection.localDescription;
    this.wsClient.send(JSON.stringify(this.pendingOffer));
    this.pendingOffer = null;
    this.clientState = ClientState.ANSWER_SENT;
  }

  /* onIceCandidate() : Callback when client receives ice candidate.
   * descritption: When client receives a new ice candidate, this callback gets called.
   * We are doing a delayed offer. 
   * We do not send the ICE candidates as it is received. 
   * Instead, we wait for ice collection to complete.
   * When there are no more candidates, we send an offer or answer 
   * to the far end.
   * When not doing a delayed offer, the candidate needs to be sent to the remote party.
   * input: Event containing ice candidate.
   * output: none
   * exception: none
   */
  async onIceCandidate(event) {
    console.log("Candidate  =  " + JSON.stringify(event));
    if (event.candidate) {
      let candidateData = {
        method: Constants.MethodRequest.ICE,
        peer_id:this.endPoint,
        farend_peer_id:this.farEndPoint,
        candidate:event.candidate
      }
      //this.wsClient.send(JSON.stringify(candidateData));
    } else {
      console.log('onIceCandidate() : No more candidates');
      if (this.role === "UAC") {
        // We are sending offer.
        this.sendOffer();
      } else if (this.role == "UAS") {
        // We are receiving offer.
        this.sendAnswer();
      }

    }

  }

  /* onRemoteTrack() : Callback when remote media is received.
   * descritption: When the RTP connection gets established and media path is set,
   * this function gets called.
   * input: Event containing an array of remote streams.
   * output: none
   * exception: none
   */  
  async onRemoteTrack(event) {
    console.log("Remote Stream Received");
    document.getElementById("farEnd").srcObject = event.streams[0];;
  }


  /* processOffer() : Called by message parser when this client receives offer.
   * descritption: When this client receives an offer, it initialises a peer connection,
   * requests local stream and adds it to the connection. Then it sets the remote sdp which it received 
   * in the message. It also sets the local description by passing the output of createAnswer();
   * input: websocket message containing the offer, and the sender's identity
   * output: none
   * exception: none
   */  
  async processOffer(message) {
    this.role = "UAS";
    this.farEndPoint = message.peer_id;
    this.initPeerConnection();
    this.clientState = ClientState.OFFER_RECEIVED;
    //https://www.html5rocks.com/en/tutorials/webrtc/basics/
    console.log("Received offer = " + JSON.stringify(message.answer));
    await this.connection.setRemoteDescription(message.offer);
    const stream =
      await navigator.mediaDevices.getUserMedia(this.constraints);
    this.localStream = stream;
    stream.getTracks().forEach((track) =>
    this.connection.addTrack(track, stream));
    document.getElementById("nearEnd").srcObject = stream;
    this.pendingOffer = message;
    await this.connection.setLocalDescription(await this.connection.createAnswer());
  }
  async processAnswer(message) {
    console.log("Received Answer = " + JSON.stringify(message.answer));
    this.clientState = ClientState.ANSWER_RECEIVED;
    await this.connection.setRemoteDescription(message.answer);
    this.clientState.IN_CALL;
  }

  /* addIce() : Called by message parser when this client receives ICE candidate.
   * descritption: Usually, every client queries stun/turn servers about how it can be reached. 
   * Stun/Turn server either return the public ip of the client or the relay IP. Either of these addresses
   * must be used by the far end to reach to this client. So the client sends this to far end.
   * Once far end receives it, it adds the candidate to its connection. 
   * 
   * This process can be done the other way. If the sdp is sent after gathering all the candidates, 
   * it will contain all the candidates. In that case, they need not be sent to the remote party.
   * output: message containing ICE candidate
   * exception: none
   */   
  async addIce(message) {
    if(message.candidate)
      this.connection.addIceCandidate(message.candidate);
  }

  /* parseMessage() : Called by by the function which receives the websocket message.
   * descritption: This function handles the incoming messages.
   * output: message
   * exception: none
   */  
  parseMessage(message) {
    switch(message.method) {
      case Constants.MethodResponse.REGISTER_RESPONSE:
        if (message.status === "SUCCESS") {
          this.clientState = ClientState.REGISTERED;
          alert("Registered!!");
        } else {
          alert("Registeration failure !!");
          this.clientState = ClientState.ERROR;
        }
        break;
        case Constants.MethodRequest.INVITE_PEER:
          //Received invite peer from far end
          if (message.farend_peer_id === this.endPoint) {
            this.processOffer(message);
          } else {
            console.log("Something wrong. Message is not intended for me");
          }
          break;
        case Constants.MethodResponse.INVITE_PEER_RESPONSE:
          if (message.peer_id === this.endPoint) {
            this.processAnswer(message);
          } else {
            console.log("Something wrong. Message is not intended for me");
          }
          break;
        case Constants.MethodRequest.ICE:
          if (message.farend_peer_id === this.endPoint) {
            this.addIce(message);
          } else {
            console.log("Something wrong. Message is not intended for me");
          }
          break;
        case Constants.MethodRequest.DISCONNECT_CALL:
          if (message.farend_peer_id === this.endPoint) {
            this.connection.close();
            this.localStream.getTracks().forEach((track) =>
            track.stop());
          } else {
            console.log("Something wrong. Message is not intended for me");
          }
          break;
    }
  }
};