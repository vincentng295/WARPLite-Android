package com.warplite.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.IOException;

/**
 * Keeps the DNS bridge (Socks5Server) alive in the background: a foreground service, so Android does not kill it.
 * Used by the 1.1.1.1 mode only.
 *
 * <p>It is also where this app learns, even with the screen off, that Magic V2Ray DISCONNECTED: while the bridge runs,
 * a state listener is registered. When Magic V2Ray reports that the session of this app is over (the user pressed
 * Disconnect in Magic V2Ray, the tile, a crash ...) nobody needs the bridge any more, so the service stops itself.
 */
public class Socks5Service extends Service {

    static final String ACTION_STOP = "com.warplite.app.STOP";
    private static final String CHANNEL = "bridge";

    /** True while the bridge accepts connections. */
    static volatile boolean listening;

    private Socks5Server server;
    private BroadcastReceiver magicReceiver;
    /** True once Magic V2Ray told us that a session of THIS app is connected. A disconnect only matters after that. */
    private boolean sessionUp;

    static void start(Context c) {
        Intent i = new Intent(c, Socks5Service.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, Socks5Service.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        showForeground();
        if (server == null) {
            try {
                int families = new WarpStore(this).settings().families;
                DohResolver doh = new DohResolver(WarpOverrides.dohUrl(families), WarpOverrides.dohFallbackUrl(families));
                server = new Socks5Server(WarpOverrides.SOCKS_PORT, doh, Log::add);
                server.start();
                listening = true;
                magicReceiver = MagicV2Ray.listen(this, this::onMagicState);
                Log.add("DNS bridge on 127.0.0.1:" + WarpOverrides.SOCKS_PORT + ", any:53 -> " + WarpOverrides.dohUrl(families));
            } catch (IOException e) {
                server = null;
                Log.add("cannot listen on 127.0.0.1:" + WarpOverrides.SOCKS_PORT + ": " + e.getMessage());
                stopForeground(true);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    /** Called (main thread) for every connect / disconnect of Magic V2Ray. */
    private void onMagicState(MagicV2Ray.StateEvent e) {
        boolean ours = e.session && (getPackageName().equals(e.caller) || MainActivity.SESSION_NAME.equals(e.sessionName));
        if (e.connected) {
            if (ours) sessionUp = true;             // our connect was accepted: from now on a disconnect ends us
        } else if (sessionUp) {
            Log.add("Magic V2Ray disconnected (" + e.reason + "), stopping the DNS bridge");
            sessionUp = false;
            shutdown();
            stopForeground(true);
            stopSelf();
        }
    }

    private void shutdown() {
        MagicV2Ray.unlisten(this, magicReceiver);
        magicReceiver = null;
        sessionUp = false;
        if (server != null) {
            server.stop();
            server = null;
            Log.add("DNS bridge stopped");
        }
        listening = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "DNS bridge", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setContentTitle("1.1.1.1 is on")
                .setContentText("DNS (port 53) is resolved over HTTPS")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }
}
