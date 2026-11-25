/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *           * Redistributions of source code must retain the above copyright
 *             notice, this list of conditions and the following disclaimer.
 *           * Redistributions in binary form must reproduce the above
 *           * copyright notice, this list of conditions and the following
 *             disclaimer in the documentation and/or other materials provided
 *             with the distribution.
 *           * Neither the name of The Linux Foundation nor the names of its
 *             contributors may be used to endorse or promote products derived
 *             from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.android.bluetooth.avrcpcontroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.MediaMetadata;
import android.os.Message;
import android.util.Log;

/**
 * AVRCP controller cover art (AVRCP 1.6 over BIP): asks the BIP client for the current
 * track's image and publishes it with the track metadata.
 *
 * Ported to the Android 10 AVRCP controller, where the current track's TrackInfo is
 * AvrcpPlayer.getCurrentTrackInfo() (getCurrentTrack() is now the MediaMetadata) and the
 * metadata is published through AvrcpPlayer.updateCurrentTrack() and
 * BluetoothMediaBrowserService.trackChanged(). The previous version was commented out to
 * compile against that controller ("A2dpSink: Remove CoverArt changes to compile SHO code").
 */
public class CoverArtUtils {

    private final String TAG = "AvrcpControllerCoverArt";

    private static final boolean DBG = true;

    static final int MESSAGE_BIP_CONNECTED = 500;
    static final int MESSAGE_BIP_DISCONNECTED = 501;
    static final int MESSAGE_BIP_THUMB_NAIL_FETCHED = 502;
    static final int MESSAGE_BIP_IMAGE_FETCHED = 503;

    public void onElementAttributeUpdate(byte[] address, byte numAttributes, int[] attributes,
            String[] attribVals, BluetoothDevice connectedDevice,
            AvrcpControllerStateMachine avrcpCtSm) {
        if (DBG) {
            Log.d(TAG, "onElementAttributeUpdate numAttributes :" + numAttributes);
        }
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
        if (device != null && !device.equals(connectedDevice)) {
            Log.e(TAG, "onElementAttributeUpdate device not found " + address);
            return;
        }
        List<Integer> attrList = new ArrayList<>();
        for (int attr : attributes) {
           attrList.add(attr);
        }
        List<String> attrValList = Arrays.asList(attribVals);
        // Attributes re-read after BIP connected: handle them as the current track, which
        // requests its image.
        TrackInfo trackInfo = new TrackInfo(attrList, attrValList);
        if (DBG) {
            Log.d(TAG, "onElementAttributeUpdate " + trackInfo);
        }
        Message msg = avrcpCtSm.obtainMessage(
                AvrcpControllerStateMachine.MESSAGE_PROCESS_TRACK_CHANGED, trackInfo);
        avrcpCtSm.sendMessage(msg);
    }

    public void msgDisconnectBip(AvrcpControllerBipStateMachine bipStateMachine,
            RemoteDevice remoteDevice) {
        if (bipStateMachine != null && remoteDevice != null) {
                bipStateMachine.sendMessage(AvrcpControllerBipStateMachine.MESSAGE_DISCONNECT_BIP,
                    remoteDevice.mBTDevice);
        }
    }

    public void msgProcessRcFeatures(AvrcpControllerBipStateMachine bipStateMachine,
            RemoteDevice remoteDevice, int remotePsm) {
        if (bipStateMachine != null && remoteDevice != null
                && remoteDevice.isCoverArtSupported()) {
            remoteDevice.setRemoteBipPsm(remotePsm);
            bipStateMachine.sendMessage(AvrcpControllerBipStateMachine.MESSAGE_CONNECT_BIP,
                remoteDevice.getRemoteBipPsm(), 0, remoteDevice.mBTDevice);
        }
    }

    /**
     * The current track changed: requests its cover art. Returns false if the track has no
     * cover art handle (the remote doesn't support cover art, or BIP isn't connected yet).
     */
    public boolean msgTrackChanged(Context ctx, AvrcpControllerBipStateMachine bipStateMachine,
            AvrcpPlayer addressedPlayer, RemoteDevice remoteDevice) {
        TrackInfo track = addressedPlayer.getCurrentTrackInfo();
        String handle = track != null ? track.getCoverArtHandle() : null;
        if (handle == null || handle.isEmpty()) {
            if (DBG && remoteDevice != null && remoteDevice.isCoverArtSupported()) {
                Log.d(TAG, " Cover Art Handle not valid ");
            }
            return false;
        }
        if (track.hasCoverArt()) {
            return true; // carried over from the previous update of the same track
        }
        fetchCoverArt(bipStateMachine, handle);
        return true;
    }

    private void fetchCoverArt(AvrcpControllerBipStateMachine bipStateMachine, String handle) {
        if (bipStateMachine == null) {
            return;
        }
        int imageOrThumbnail;
        if ("thumbnail".equalsIgnoreCase(AvrcpControllerBipStateMachine.mImageType)) {
            imageOrThumbnail = AvrcpControllerBipStateMachine.MESSAGE_FETCH_THUMBNAIL;
        } else {
            imageOrThumbnail = AvrcpControllerBipStateMachine.MESSAGE_FETCH_IMAGE;
        }
        if (DBG) {
            Log.d(TAG, " fetch cover art, handle " + handle + " type "
                    + AvrcpControllerBipStateMachine.mImageType);
        }
        bipStateMachine.sendMessage(imageOrThumbnail, handle);
    }

    public void processBipAction(Context ctx, AvrcpPlayer addressedPlayer,
            RemoteDevice remoteDevice, int bipAction, Message msg,
            AvrcpControllerBipStateMachine bipStateMachine) {
        if (DBG) {
            Log.d(TAG, " processBipAction bipAction: " + bipAction);
        }
        TrackInfo track = addressedPlayer.getCurrentTrackInfo();
        switch (bipAction) {
            case MESSAGE_BIP_CONNECTED:
                if ((track == null || track.getCoverArtHandle().isEmpty())
                        && remoteDevice != null) {
                    /*
                     * The track changed before BIP connected, so it has no cover art handle:
                     * fetch the attributes again (NumAttributes = 0 and a null attribute list
                     * fetch all of them); the reply comes back as onElementAttributeUpdate().
                     */
                    AvrcpControllerService.getElementAttributesNative(
                            remoteDevice.getBluetoothAddress(), (byte) 0, null);
                } else if (track != null && !track.getCoverArtHandle().isEmpty()
                        && !track.hasCoverArt()) {
                    fetchCoverArt(bipStateMachine, track.getCoverArtHandle());
                }
                break;
            case MESSAGE_BIP_DISCONNECTED:
                // Clear the current track's cover art; its handle is only valid on that session.
                if (track != null) {
                    boolean hadCoverArt = track.hasCoverArt();
                    track.clearCoverArtData();
                    if (hadCoverArt) {
                        publish(addressedPlayer, track);
                    }
                }
                break;
            case MESSAGE_BIP_IMAGE_FETCHED:
                if (track != null && track.updateImageLocation(
                        msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
                        msg.getData()
                            .getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION))) {
                    publish(addressedPlayer, track);
                }
                break;
            case MESSAGE_BIP_THUMB_NAIL_FETCHED:
                if (track != null && track.updateThumbNailLocation(
                        msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
                        msg.getData()
                            .getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION))) {
                    publish(addressedPlayer, track);
                }
                break;
        }
    }

    /** Publishes the track's metadata (now with or without cover art) to media apps. */
    private void publish(AvrcpPlayer addressedPlayer, TrackInfo track) {
        MediaMetadata metadata = track.getMediaMetaData();
        if (DBG) {
            Log.d(TAG, " publish metadata, cover art " + track.hasCoverArt());
        }
        addressedPlayer.updateCurrentTrack(metadata);
        BluetoothMediaBrowserService.trackChanged(metadata);
    }

    public String dumpMessageString(int message) {
        String str = "";
        switch (message) {
            case MESSAGE_BIP_CONNECTED: str = "BIP_CONNECTED"; break;
            case MESSAGE_BIP_DISCONNECTED: str = "BIP_DISCONNECTED"; break;
            case MESSAGE_BIP_IMAGE_FETCHED: str = "BIP_IMAGE_FETCHED"; break;
            case MESSAGE_BIP_THUMB_NAIL_FETCHED: str = "BIP_THUMB_NAIL_FETCHED";
        }
        return str;
    }

    public void closeBip(AvrcpControllerBipStateMachine bipStateMachine) {
        if (bipStateMachine != null) {
            bipStateMachine.doQuit();
            if (DBG) {
                Log.d(TAG, "mBipStateMachine doQuit ");
            }
        }
    }
}
