/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.videortc;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.support.annotation.UiThread;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import org.appspot.apprtc.AppRTCClient;
import org.appspot.apprtc.PeerConnectionClient;
import org.appspot.apprtc.WebSocketRTCClient;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.RendererCommon.ScalingType;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import util.ProxyVideoRendererCallbacks;
import util.ProxyVideoSink;

public class CallActivity extends Activity implements AppRTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents {
    static final String TAG = "CallActivity";

    // Peer connection statistics callback period in ms.
    static final int STAT_CALLBACK_PERIOD = 1000;
    ProxyVideoRendererCallbacks remoteVideo;
    ProxyVideoSink localVideo;
    List<VideoRenderer.Callbacks> remoteRenderers;
    PeerConnectionClient peerConnectionClient;
    AppRTCClient appRtcClient;
    AppRTCClient.SignalingParameters signalingParameters;

    Toast logToast;
    boolean activityRunning;
    AppRTCClient.RoomConnectionParameters roomConnectionParameters;
    PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;

    boolean iceConnected;
    boolean isError;

    boolean micEnabled = true;
    boolean isSwappedFeeds;
    // UI Elements
    SurfaceViewRenderer svrSmall;
    SurfaceViewRenderer svrFull;
    ImageButton buttonToggleMute;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        iceConnected = false;
        signalingParameters = null;

        // Create UI controls.
        ImageButton disconnectButton = findViewById(R.id.button_call_disconnect);
        ImageButton cameraSwitchButton = findViewById(R.id.button_call_switch_camera);
        buttonToggleMute = findViewById(R.id.button_call_toggle_mic);
        disconnectButton.setOnClickListener(v -> onCallHangUp());
        cameraSwitchButton.setOnClickListener(view -> onCameraSwitch());
        buttonToggleMute.setOnClickListener(view -> {
            if (peerConnectionClient != null) {
                micEnabled = !micEnabled;
                peerConnectionClient.setAudioEnabled(micEnabled);
            }
            buttonToggleMute.setAlpha(micEnabled ? 1.0f : 0.3f);
        });

        // Video Setup
        remoteVideo = new ProxyVideoRendererCallbacks();
        remoteRenderers = new ArrayList<>();
        remoteRenderers.add(remoteVideo);
        localVideo = new ProxyVideoSink();

        peerConnectionClient = new PeerConnectionClient();

        // Create video renderers.
        svrSmall = findViewById(R.id.pip_video_view);
        svrSmall.setOnClickListener(view -> setSwappedFeeds(!isSwappedFeeds)); // Swap feeds on pip view click.
        svrSmall.init(peerConnectionClient.getRenderContext(), null);
        svrSmall.setScalingType(ScalingType.SCALE_ASPECT_FIT);
        svrSmall.setZOrderMediaOverlay(true);
        svrSmall.setEnableHardwareScaler(true);

        svrFull = findViewById(R.id.fullscreen_video_view);
        svrFull.init(peerConnectionClient.getRenderContext(), null);
        svrFull.setScalingType(ScalingType.SCALE_ASPECT_FILL);
        svrFull.setEnableHardwareScaler(true);

        // Start with local feed in fullscreen and swap it to the pip when the call is connected
        setSwappedFeeds(true);

        // Generate a random room ID with 7 uppercase letters and digits
        String randomRoomID = "JESUS-" + new Random().nextInt(100);
        // Show the random room ID so that another client can join from https://appr.tc
        TextView roomIdTextView = findViewById(R.id.roomID);
        roomIdTextView.setText(getString(R.string.room_id_caption) + randomRoomID);
        Log.d(TAG, getString(R.string.room_id_caption) + randomRoomID);

        // Connect video call to the random room
        connectVideoCall(randomRoomID);
    }

    // Join video call with randomly generated roomId
    private void connectVideoCall(String roomId) {
        peerConnectionParameters = new PeerConnectionClient.PeerConnectionParameters(
                true,
                false,
                false,
                0,
                0,
                0,
                1700,
                "VP8",
                true,
                false,
                32,
                "OPUS",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);

        peerConnectionClient.createPeerConnectionFactory(
                getApplicationContext(),
                peerConnectionParameters,
                CallActivity.this);

        // create connection parameters
        roomConnectionParameters = new AppRTCClient.RoomConnectionParameters(
                "https://appr.tc",
                roomId,
                false,
                null);

        // start room connection
        // create connection client. use the standard WebSocketRTCClient
        // DirectRTCClient could be used for point-to-point connection
        logAndToast(getString(R.string.connecting_to, "https://appr.tc"));
        appRtcClient = new WebSocketRTCClient(this);
        appRtcClient.connectToRoom(roomConnectionParameters);
    }

    public void onCallHangUp() {
        disconnect();
    }

    public void onCameraSwitch() {
        if (peerConnectionClient != null) {
            peerConnectionClient.switchCamera();
        }
    }

    @UiThread
    private void callConnected() {
        Log.i(TAG, "Call connected");
        if (peerConnectionClient == null || isError) {
            Log.w(TAG, "Call is connected in closed or error state");
            return;
        }
        // Enable statistics callback.
        peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
        setSwappedFeeds(false);
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        activityRunning = false;
        remoteVideo.setTarget(null);
        localVideo.setTarget(null);
        if (appRtcClient != null) {
            appRtcClient.disconnectFromRoom();
            appRtcClient = null;
        }
        if (svrSmall != null) {
            svrSmall.release();
            svrSmall = null;
        }
        if (svrFull != null) {
            svrFull.release();
            svrFull = null;
        }
        if (peerConnectionClient != null) {
            peerConnectionClient.close();
            peerConnectionClient = null;
        }
        if (iceConnected && !isError) {
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (!activityRunning) {
            Log.e(TAG, "Critical error: " + errorMessage);
            disconnect();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(getText(R.string.channel_error_title))
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setNeutralButton(R.string.ok,
                            (dialog, id) -> {
                                dialog.cancel();
                                disconnect();
                            })
                    .create()
                    .show();
        }
    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg) {
        Log.d(TAG, msg);
        if (logToast != null) {
            logToast.cancel();
        }
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        logToast.show();
    }

    private void reportError(final String description) {
        runOnUiThread(() -> {
            if (!isError) {
                isError = true;
                disconnectWithErrorMessage(description);
            }
        });
    }

    // Create VideoCapturer
    private VideoCapturer createVideoCapturer() {
        final VideoCapturer videoCapturer;
        Logging.d(TAG, "Creating capturer using camera2 API.");
        videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        if (videoCapturer == null) {
            reportError("Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    // Create VideoCapturer from camera
    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private void setSwappedFeeds(boolean isSwappedFeeds) {
        Logging.d(TAG, "setSwappedFeeds: " + isSwappedFeeds);
        this.isSwappedFeeds = isSwappedFeeds;
        localVideo.setTarget(isSwappedFeeds ? svrFull : svrSmall);
        remoteVideo.setTarget(isSwappedFeeds ? svrSmall : svrFull);
        svrFull.setMirror(isSwappedFeeds);
        svrSmall.setMirror(!isSwappedFeeds);
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    @Override
    public void onConnectedToRoom(final AppRTCClient.SignalingParameters params) {
        runOnUiThread(() -> {
            signalingParameters = params;
            logAndToast("Creating peer connection");

            if (peerConnectionParameters.videoCallEnabled) {
                peerConnectionClient.createPeerConnection(localVideo, remoteRenderers, createVideoCapturer(), signalingParameters);
            } else {
                peerConnectionClient.createPeerConnection(localVideo, remoteRenderers, null, signalingParameters);
            }

            if (signalingParameters.initiator) {
                logAndToast("Creating OFFER...");
                // Create offer. Offer SDP will be sent to answering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient.createOffer();
            } else {
                if (params.offerSdp != null) {
                    peerConnectionClient.setRemoteDescription(params.offerSdp);
                    logAndToast("Creating ANSWER...");
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    peerConnectionClient.createAnswer();
                }
                if (params.iceCandidates != null) {
                    // Add remote ICE candidates from room.
                    for (IceCandidate iceCandidate : params.iceCandidates) {
                        peerConnectionClient.addRemoteIceCandidate(iceCandidate);
                    }
                }
            }
        });
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        runOnUiThread(() -> {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                return;
            }
            logAndToast("Received remote SDP.type: " + sdp.type);
            peerConnectionClient.setRemoteDescription(sdp);
            if (!signalingParameters.initiator) {
                logAndToast("Creating ANSWER...");
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient.createAnswer();
            }
        });
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        runOnUiThread(() -> {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.");
                return;
            }
            peerConnectionClient.addRemoteIceCandidate(candidate);
        });
    }

    @Override
    public void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(() -> {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.");
                return;
            }
            peerConnectionClient.removeRemoteIceCandidates(candidates);
        });
    }

    @Override
    public void onChannelClose() {
        runOnUiThread(() -> {
            logAndToast("Remote end hung up; dropping PeerConnection");
            disconnect();
        });
    }

    @Override
    public void onChannelError(final String description) {
        reportError(description);
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        runOnUiThread(() -> {
            if (appRtcClient != null) {
                logAndToast("Sending SDP.type" + sdp.type);
                if (signalingParameters.initiator) {
                    appRtcClient.sendOfferSdp(sdp);
                } else {
                    appRtcClient.sendAnswerSdp(sdp);
                }
            }
            if (peerConnectionParameters.videoMaxBitrate > 0) {
                Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters.videoMaxBitrate);
                peerConnectionClient.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
            }
        });
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        runOnUiThread(() -> {
            if (appRtcClient != null) {
                appRtcClient.sendLocalIceCandidate(candidate);
            }
        });
    }

    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        runOnUiThread(() -> {
            if (appRtcClient != null) {
                appRtcClient.sendLocalIceCandidateRemovals(candidates);
            }
        });
    }

    @Override
    public void onIceConnected() {
        runOnUiThread(() -> {
            logAndToast("ICE connected");
            iceConnected = true;
            callConnected();
        });
    }

    @Override
    public void onIceDisconnected() {
        runOnUiThread(() -> {
            logAndToast("ICE disconnected");
            iceConnected = false;
            disconnect();
        });
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    }

    @Override
    public void onPeerConnectionError(final String description) {
        reportError(description);
    }

    // Activity interfaces
    @Override
    public void onStop() {
        super.onStop();
        activityRunning = false;
        if (peerConnectionClient != null) {
            peerConnectionClient.stopVideoSource();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        activityRunning = true;
        // Video is not paused for screencapture. See onPause.
        if (peerConnectionClient != null) {
            peerConnectionClient.startVideoSource();
        }
    }

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        disconnect();
        if (logToast != null) {
            logToast.cancel();
        }
        activityRunning = false;
        super.onDestroy();
    }
}
