package com.local.karaoke.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.local.karaoke.R;
import com.local.karaoke.data.LibraryRepository;
import com.local.karaoke.data.Prefs;

public class SettingsActivity extends AppCompatActivity {

    private TextView dirPath;

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) applyFolder(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        dirPath = findViewById(R.id.dir_path);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_change_dir).setOnClickListener(v -> folderLauncher.launch(null));

        updatePath();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePath();
    }

    private void updatePath() {
        String value = Prefs.getTreeUri(this);
        if (value == null) {
            dirPath.setText(R.string.not_set);
            return;
        }
        dirPath.setText(displayPath(value));
    }

    private String displayPath(String value) {
        try {
            Uri uri = Uri.parse(value);
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null) {
                int colon = docId.indexOf(':');
                String volume = colon > 0 ? docId.substring(0, colon) : docId;
                String path = colon >= 0 ? docId.substring(colon + 1) : "";
                String volumeName;
                if ("primary".equalsIgnoreCase(volume)) {
                    volumeName = "内部存储";
                } else if (volume != null && volume.toLowerCase().startsWith("home")) {
                    volumeName = "文档";
                } else {
                    volumeName = "存储卡 / U盘";
                }
                if (path.isEmpty()) return volumeName;
                return volumeName + " / " + path;
            }
        } catch (Throwable ignored) {
        }
        return value;
    }

    private void applyFolder(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        Prefs.setTreeUri(this, uri.toString());
        LibraryRepository.get(this).scan();
        updatePath();
        Toast.makeText(this, R.string.folder_changed, Toast.LENGTH_SHORT).show();
    }
}
