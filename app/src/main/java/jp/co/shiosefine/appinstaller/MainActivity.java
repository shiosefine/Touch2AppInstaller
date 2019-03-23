package jp.co.shiosefine.appinstaller;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import jp.co.benesse.dcha.dchaservice.IDchaService;

public class MainActivity extends Activity implements View.OnClickListener{
    private static final int RESULT_CODE_CHOOSE_FILE = 1;
    private IDchaService mDchaService;
    private Button buttonInstallApp;
    private Handler handler;
    private String installPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler();

        buttonInstallApp = findViewById(R.id.button_install_app);
        buttonInstallApp.setEnabled(false);
        buttonInstallApp.setText("インストールするアプリを選択");

        bindDchaService(new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                mDchaService = IDchaService.Stub.asInterface(iBinder);

                buttonInstallApp.setEnabled(true);
                buttonInstallApp.setOnClickListener(MainActivity.this);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                mDchaService = null;
            }
        });
    }


    public static boolean installApp(Context context, IDchaService mDchaService, String path, int flag) {
        File tempDir = new File(Environment.getExternalStorageDirectory(), BuildConfig.APPLICATION_ID);

        // 必要なディレクトリとファイルを生成
        if (!tempDir.mkdir()) {
            Log.e("Touch2Test", "installApp failed because mkdir returned false");
        }
        final File tempApkSig = new File(tempDir, "temp.apk");
        final File tempApkSig2 = new File(tempDir, "temp-2.apk");
        final File tempApkInstall = new File(tempDir, "temp-1.apk");

        // インストールするファイルをコピー
        File installFile = new File(path);
        if (! copyFile(installFile, tempApkInstall)) {
            Log.e("Touch2Test", "installApp failed because renameTo1 returned false");
            return false; // ファイルの移動に失敗したら終わり
        }

        // すり替えるための署名apkを生成
        InputStream inputStream = context.getResources().openRawResource(R.raw.blue);
        FileOutputStream fileOutputStream;
        try {
            fileOutputStream = new FileOutputStream(tempApkSig);
        } catch (FileNotFoundException e) {
            Log.e("Touch2Test", "FileNotFoundException", e);
            return false;
        }
        byte buf[] = new byte[1024];
        int len;
        try {
            while ((len = inputStream.read(buf)) != -1) {
                fileOutputStream.write(buf, 0, len);
            }
            fileOutputStream.flush();
            fileOutputStream.close();
            inputStream.close();
        } catch (IOException e) {
            Log.e("Touch2Test", "IOException", e);
            return false;
        }

        // DchaServiceの署名チェックらしいアクセスを検知したら、ファイルをすり替えるためのオブザーバー
        final FileObserver sigFileObserver = new FileObserver(tempApkSig.getPath()) {
            private boolean flag = true;

            @Override
            public void onEvent(int i, String s) {
                if (i == FileObserver.CLOSE_NOWRITE && flag) {
                    flag = false;
                    // アクセスを検知したら、ファイルをすり替え
                    if (tempApkSig.renameTo(tempApkSig2)) {
                        if (tempApkInstall.renameTo(tempApkSig)) {
                        } else {
                            Log.e("Touch2Test", "installApp failed because renameTo3 returned false");
                        }
                    } else {
                        Log.e("Touch2Test", "installApp failed because renameTo2 returned false");
                    }
                    this.stopWatching();
                }
            }
        };
        sigFileObserver.startWatching();
        boolean bool = false;
        try {
            bool = mDchaService.installApp(tempApkSig.getPath(), flag);
        } catch (RemoteException e) {
            Log.e("Touch2Test", "installApp failed because throw RemoteException", e);
            return false;
        }

        // 作成したファイルとディレクトリを削除
        tempApkInstall.delete();
        tempApkSig.delete();
        tempApkSig2.delete();
        tempDir.delete();
        return bool;
    }

    private static boolean copyFile(File fromFile, File toFile) {
        try {
            FileInputStream fis = new FileInputStream(fromFile);
            FileOutputStream fos = new FileOutputStream(toFile);
            byte buf[] = new byte[1024];
            int len;
            while ((len = fis.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            fos.close();
            fis.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showResultToast(boolean isSuccess) {
        Toast.makeText(this, isSuccess ? "成功しました。" : "失敗しました。", Toast.LENGTH_LONG).show();
    }

    private boolean bindDchaService(ServiceConnection serviceConnection) {
        Intent intent = new Intent("jp.co.benesse.dcha.dchaservice.DchaService");
        intent.setPackage("jp.co.benesse.dcha.dchaservice");
        return bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onClick(View view) {
        installPath = null;

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, RESULT_CODE_CHOOSE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESULT_CODE_CHOOSE_FILE) {
            if (resultCode == RESULT_OK) {
                buttonInstallApp.setEnabled(false);
                buttonInstallApp.setText("インストール中...");
                installPath = getPathFromUri(MainActivity.this, data.getData());
                new Thread(runnable).start();
            }
        }
    }

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            final boolean bool = installApp(MainActivity.this, mDchaService, installPath, 1);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    showResultToast(bool);
                    buttonInstallApp.setEnabled(true);
                    buttonInstallApp.setText("インストールするアプリを選択");
                }
            });
        }
    };

    // https://stackoverflow.com/questions/32661221/android-cursor-didnt-have-data-column-not-found/33930169#33930169より引用
    private String getPathFromUri(final Context context, final Uri uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if ("com.android.externalstorage.documents".equals(
                    uri.getAuthority())) {// ExternalStorageProvider
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }else {
                    return "/storage/" + type +  "/" + split[1];
                }
            }else if ("com.android.providers.downloads.documents".equals(
                    uri.getAuthority())) {// DownloadsProvider
                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                return getDataColumn(context, contentUri, null, null);
            }else if ("com.android.providers.media.documents".equals(
                    uri.getAuthority())) {// MediaProvider
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                Uri contentUri;
                contentUri = MediaStore.Files.getContentUri("external");
                final String selection = "_id=?";
                final String[] selectionArgs = new String[] {
                        split[1]
                };
                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }else if ("content".equalsIgnoreCase(uri.getScheme())) {//MediaStore
            return getDataColumn(context, uri, null, null);
        }else if ("file".equalsIgnoreCase(uri.getScheme())) {// File
            return uri.getPath();
        }
        return null;
    }

    private static String getDataColumn(Context context, Uri uri, String selection,
                                        String[] selectionArgs) {
        Cursor cursor = null;
        final String[] projection = {
                MediaStore.Files.FileColumns.DATA
        };
        try {
            cursor = context.getContentResolver().query(
                    uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int cindex = cursor.getColumnIndexOrThrow(projection[0]);
                return cursor.getString(cindex);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }
}
