package com.winnie.xiaozhou;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 小周血压本：用 WebView 打开网页版，并补上浏览器“桌面模式”在华为 P40 上缺的东西：
 * 相机权限、拍照/相册选择、保存导出的表格。
 */
public class MainActivity extends Activity {

    private static final String APP_URL = "https://winniewangxy.github.io/1/";
    private static final int REQ_CAMERA_FOR_PAGE = 1;
    private static final int REQ_CAMERA_FOR_CHOOSER = 2;
    private static final int REQ_FILE = 10;

    private WebView web;
    private PermissionRequest pendingPagePermission;
    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient.FileChooserParams pendingChooserParams;
    private Uri cameraOutput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#FBF6F7"));
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FBF6F7"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                 // 不跟着系统大字体再放大一遍
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " XiaozhouApp/1.0");

        web.addJavascriptInterface(new Bridge(), "XiaozhouApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("winniewangxy.github.io".equals(u.getHost())) return false;   // 自己的页面留在 App 里
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // 网页里的“小周自带相机”（getUserMedia）
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsCamera = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                    }
                    if (!wantsCamera) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingPagePermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_FOR_PAGE);
                    }
                });
            }

            // 网页里的“相册”“用手机相机拍”“导入以前的记录”
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (wantsImage(params) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingChooserParams = params;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_FOR_CHOOSER);
                    return true;
                }
                openChooser(params);
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
    }

    private boolean wantsImage(WebChromeClient.FileChooserParams p) {
        if (p == null) return false;
        String[] types = p.getAcceptTypes();
        if (types == null) return false;
        for (String t : types) if (t != null && t.startsWith("image")) return true;
        return false;
    }

    private void openChooser(WebChromeClient.FileChooserParams params) {
        boolean image = wantsImage(params);
        boolean capture = params != null && params.isCaptureEnabled();

        Intent camera = null;
        cameraOutput = null;
        if (image && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "xiaozhou_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/小周血压本");
                cameraOutput = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (cameraOutput != null) {
                    camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutput);
                    camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception e) { camera = null; cameraOutput = null; }
        }

        try {
            if (capture && camera != null) {           // “用手机相机拍”：直接开相机
                startActivityForResult(camera, REQ_FILE);
                return;
            }
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            if (image) {
                pick.setType("image/*");
            } else {
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                        "text/plain", "application/octet-stream"});
            }
            Intent chooser = Intent.createChooser(pick, image ? "选择照片" : "选择表格");
            if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            startActivityForResult(chooser, REQ_FILE);
        } catch (Exception e) {
            if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
            Toast.makeText(this, "打不开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            List<Uri> uris = new ArrayList<>();
            if (data != null && data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data != null && data.getData() != null) {
                uris.add(data.getData());
            } else if (cameraOutput != null) {
                uris.add(cameraOutput);                   // 相机拍的
            }
            if (!uris.isEmpty()) result = uris.toArray(new Uri[0]);
        }
        if (result == null && cameraOutput != null) {   // 没拍：把空的相片记录删掉
            try { getContentResolver().delete(cameraOutput, null, null); } catch (Exception ignored) { }
        }
        cameraOutput = null;
        if (fileCallback != null) { fileCallback.onReceiveValue(result); fileCallback = null; }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAMERA_FOR_PAGE && pendingPagePermission != null) {
            if (ok) pendingPagePermission.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingPagePermission.deny();
            pendingPagePermission = null;
        } else if (requestCode == REQ_CAMERA_FOR_CHOOSER) {
            openChooser(pendingChooserParams);           // 没给相机权限也能从相册选
            pendingChooserParams = null;
        }
    }

    @Override
    public void onBackPressed() {
        // 先让网页关掉打开着的窗口（相机、记一次血压、导入……），没有窗口才退出
        web.evaluateJavascript("window.__xzBack ? String(window.__xzBack()) : 'false'", value -> {
            if (value == null || !value.contains("true")) {
                if (web.canGoBack()) web.goBack();
                else MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    /** 网页调用：把导出的表格存到“下载”文件夹，然后弹出分享（可以发微信） */
    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String base64, String mime, boolean share) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/小周血压本");
                final Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "fail";
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return "fail";
                    out.write(bytes);
                }
                if (share) {
                    runOnUiThread(() -> {
                        try {
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType(mime);
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, "发送表格"));
                        } catch (Exception ignored) { }
                    });
                }
                return "ok";
            } catch (Exception e) {
                return "fail";
            }
        }

        @JavascriptInterface
        public String version() { return "1.0"; }
    }
}
