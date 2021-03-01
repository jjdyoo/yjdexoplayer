package com.example.jdexoplay;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int MY_PERMISSION_READ = 1;
    private static final int REQUEST_TAKE_ALBUM = 3;

    Button btn_album;
    Uri photoURI;
    Intent VideoIntent;

    private String DEFAULT_MEDIA_URI =
            "https://storage.googleapis.com/exoplayer-test-media-1/mkv/android-screens-lavf-56.36.100-aac-avc-main-1280x720.mkv";
    private static final String SURFACE_CONTROL_NAME = "surfacedemo";

    private static final String ACTION_VIEW = "com.google.android.exoplayer.surfacedemo.action.VIEW";
    private static final String EXTENSION_EXTRA = "extension";
    private static final String DRM_SCHEME_EXTRA = "drm_scheme";
    private static final String DRM_LICENSE_URL_EXTRA = "drm_license_url";
    private static final String OWNER_EXTRA = "owner";

    private boolean isOwner;
    @Nullable
    private PlayerControlView playerControlView;
    @Nullable private SurfaceView fullScreenView;
    @Nullable private SurfaceView nonFullScreenView;
    @Nullable private SurfaceView currentOutputView;

    @Nullable private static SimpleExoPlayer player;
    @Nullable private static SurfaceControl surfaceControl;
    @Nullable private static Surface videoSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        playerControlView = findViewById(R.id.player_control_view);
        fullScreenView = findViewById(R.id.full_screen_view);
        fullScreenView.setOnClickListener(
                v -> {
                    setCurrentOutputView(nonFullScreenView);
                    Assertions.checkNotNull(fullScreenView).setVisibility(View.GONE);
                });
        attachSurfaceListener(fullScreenView);
        isOwner = getIntent().getBooleanExtra(OWNER_EXTRA, /* defaultValue= */ true);
        GridLayout gridLayout = findViewById(R.id.grid_layout);

        for (int i = 0; i < 9; i++) {
            View view;
            if (i == 0) {
                Button button = new Button(/* context= */ this);
                view = button;
                button.setText(getString(R.string.no_output_label));
                button.setOnClickListener(v -> reparent(/* surfaceView= */ null));
            } else if (i == 1) {
                Button button = new Button(/* context= */ this);
                view = button;
                button.setText(getString(R.string.full_screen_label));
                button.setOnClickListener(
                        v -> {
                            setCurrentOutputView(fullScreenView);
                            Assertions.checkNotNull(fullScreenView).setVisibility(View.VISIBLE);
                        });
            } else if (i == 2) {
                Button button = new Button(/* context= */ this);
                view = button;
                button.setText(getString(R.string.new_activity_label));
                button.setOnClickListener(
                        v ->
                                startActivity(
                                        new Intent(MainActivity.this, MainActivity.class)
                                                .putExtra(OWNER_EXTRA, /* value= */ false)));
            } else {
                SurfaceView surfaceView = new SurfaceView(this);
                view = surfaceView;
                attachSurfaceListener(surfaceView);
                surfaceView.setOnClickListener(
                        v -> {
                            setCurrentOutputView(surfaceView);
                            nonFullScreenView = surfaceView;
                        });
                if (nonFullScreenView == null) {
                    nonFullScreenView = surfaceView;
                }
            }
            gridLayout.addView(view);
            GridLayout.LayoutParams layoutParams = new GridLayout.LayoutParams();
            layoutParams.width = 0;
            layoutParams.height = 0;
            layoutParams.columnSpec = GridLayout.spec(i % 3, 1f);
            layoutParams.rowSpec = GridLayout.spec(i / 3, 1f);
            layoutParams.bottomMargin = 10;
            layoutParams.leftMargin = 10;
            layoutParams.topMargin = 10;
            layoutParams.rightMargin = 10;
            view.setLayoutParams(layoutParams);
        }

        btn_album = (Button) findViewById(R.id.btn_album);
        btn_album.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getAlbum();
            }
        });

        checkPermission();
    }

    private void attachSurfaceListener(SurfaceView surfaceView) {
        surfaceView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder surfaceHolder) {
                                if (surfaceView == currentOutputView) {
                                    reparent(surfaceView);
                                }
                            }

                            @Override
                            public void surfaceChanged(
                                    SurfaceHolder surfaceHolder, int format, int width, int height) {}

                            @Override
                            public void surfaceDestroyed(SurfaceHolder surfaceHolder) {}
                        });
    }

    private void initializePlayer() {
        Intent intent = getIntent();
        String action = intent.getAction();
        Uri uri =
                ACTION_VIEW.equals(action)
                        ? Assertions.checkNotNull(intent.getData())
                        : Uri.parse(DEFAULT_MEDIA_URI);
        DrmSessionManager drmSessionManager;
        if (intent.hasExtra(DRM_SCHEME_EXTRA)) {
            String drmScheme = Assertions.checkNotNull(intent.getStringExtra(DRM_SCHEME_EXTRA));
            String drmLicenseUrl = Assertions.checkNotNull(intent.getStringExtra(DRM_LICENSE_URL_EXTRA));
            UUID drmSchemeUuid = Assertions.checkNotNull(Util.getDrmUuid(drmScheme));
            HttpDataSource.Factory licenseDataSourceFactory = new DefaultHttpDataSourceFactory();
            HttpMediaDrmCallback drmCallback =
                    new HttpMediaDrmCallback(drmLicenseUrl, licenseDataSourceFactory);
            drmSessionManager =
                    new DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(drmSchemeUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                            .build(drmCallback);
        } else {
            drmSessionManager = DrmSessionManager.DRM_UNSUPPORTED;
        }

        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this);
        MediaSource mediaSource;
        @C.ContentType int type = Util.inferContentType(uri, intent.getStringExtra(EXTENSION_EXTRA));
        if (type == C.TYPE_DASH) {
            mediaSource =
                    new DashMediaSource.Factory(dataSourceFactory)
                            .setDrmSessionManager(drmSessionManager)
                            .createMediaSource(MediaItem.fromUri(uri));
        } else if (type == C.TYPE_OTHER) {
            mediaSource =
                    new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .setDrmSessionManager(drmSessionManager)
                            .createMediaSource(MediaItem.fromUri(uri));
        } else {
            throw new IllegalStateException();
        }
        SimpleExoPlayer player = new SimpleExoPlayer.Builder(getApplicationContext()).build();
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);

        surfaceControl =
                new SurfaceControl.Builder()
                        .setName(SURFACE_CONTROL_NAME)
                        .setBufferSize(/* width= */ 0, /* height= */ 0)
                        .build();
        videoSurface = new Surface(surfaceControl);
        player.setVideoSurface(videoSurface);
        MainActivity.player = player;
    }

    private static void reparent(@Nullable SurfaceView surfaceView) {
        SurfaceControl surfaceControl = Assertions.checkNotNull(MainActivity.surfaceControl);
        if (surfaceView == null) {
            new SurfaceControl.Transaction()
                    .reparent(surfaceControl, /* newParent= */ null)
                    .setBufferSize(surfaceControl, /* w= */ 0, /* h= */ 0)
                    .setVisibility(surfaceControl, /* visible= */ false)
                    .apply();
        } else {
            SurfaceControl newParentSurfaceControl = surfaceView.getSurfaceControl();
            new SurfaceControl.Transaction()
                    .reparent(surfaceControl, newParentSurfaceControl)
                    .setBufferSize(surfaceControl, surfaceView.getWidth(), surfaceView.getHeight())
                    .setVisibility(surfaceControl, /* visible= */ true)
                    .apply();
        }
    }

    private void setCurrentOutputView(@Nullable SurfaceView surfaceView) {
        currentOutputView = surfaceView;
        if (surfaceView != null && surfaceView.getHolder().getSurface() != null) {
            reparent(surfaceView);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (isOwner && player == null) {
            initializePlayer();
        }

        setCurrentOutputView(nonFullScreenView);

        PlayerControlView playerControlView = Assertions.checkNotNull(this.playerControlView);
        playerControlView.setPlayer(player);
        playerControlView.show();
    }

    @Override
    public void onPause() {
        super.onPause();

        Assertions.checkNotNull(playerControlView).setPlayer(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isOwner && isFinishing()) {
            if (surfaceControl != null) {
                surfaceControl.release();
                surfaceControl = null;
            }
            if (videoSurface != null) {
                videoSurface.release();
                videoSurface = null;
            }
            if (player != null) {
                player.release();
                player = null;
            }
        }
    }

    private void getAlbum(){
        onPause();
        player = null;

        Log.i("getAlbum", "Call");
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("video/*");
        intent.setType(MediaStore.Video.Media.CONTENT_TYPE);
        startActivityForResult(intent, REQUEST_TAKE_ALBUM);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case REQUEST_TAKE_ALBUM:
                if (resultCode == Activity.RESULT_OK) {

                    if(data.getData() != null){
                        try {
                            DEFAULT_MEDIA_URI = String.valueOf(data.getData());
                            onResume();
                        }catch (Exception e){
                            Log.e("TAKE_ALBUM_SINGLE ERROR", e.toString());
                        }
                    }
                }
                break;
        }
    }

    private void checkPermission(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // 처음 호출시엔 if()안의 부분은 false로 리턴 됨 -> else{..}의 요청으로 넘어감
            if ((ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE))) {
                new AlertDialog.Builder(this)
                        .setTitle("알림")
                        .setMessage("저장소 권한이 거부되었습니다. 사용을 원하시면 설정에서 해당 권한을 직접 허용하셔야 합니다.")
                        .setNeutralButton("설정", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        })
                        .setPositiveButton("확인", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                finish();
                            }
                        })
                        .setCancelable(false)
                        .create()
                        .show();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MY_PERMISSION_READ);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSION_READ:
                for (int i = 0; i < grantResults.length; i++) {
                    // grantResults[] : 허용된 권한은 0, 거부한 권한은 -1
                    if (grantResults[i] < 0) {
                        Toast.makeText(MainActivity.this, "해당 권한을 활성화 하셔야 합니다.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                // 허용했다면 이 부분에서..

                break;
        }
    }
}