package com.winlator.saves;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.R;
import com.winlator.core.AppUtils;

import java.io.File;

public class CustomFilePickerActivity extends AppCompatActivity {

    private File currentDirectory;
    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private Button confirmButton;
    private Button upButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_picker);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        confirmButton = findViewById(R.id.confirmButton);
        upButton = findViewById(R.id.upButton);
        TextView pickerTitle = findViewById(R.id.TVPickerTitle);

        String initialDirectoryPath = getIntent().getStringExtra("initialDirectory");
        currentDirectory = new File(initialDirectoryPath != null ? initialDirectoryPath : "/");

        boolean isEditing = getIntent().getBooleanExtra("isEditing", false);
        if (isEditing) {
            String editingPath = getIntent().getStringExtra("editingPath");
            if (editingPath != null) {
                currentDirectory = new File(editingPath);
            }
        }

        loadFiles(currentDirectory);

        confirmButton.setOnClickListener(view -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedDirectory", currentDirectory.getAbsolutePath());
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        });

        upButton.setOnClickListener(view -> {
            File parentDirectory = currentDirectory.getParentFile();
            if (parentDirectory != null) {
                currentDirectory = parentDirectory;
                loadFiles(currentDirectory);
                confirmButton.setEnabled(false);
            }
        });
    }

    private void loadFiles(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> {
                int value = Boolean.compare(!a.isDirectory(), !b.isDirectory());
                if (value == 0) value = a.getName().compareToIgnoreCase(b.getName());
                return value;
            });
            fileAdapter = new FileAdapter(files, this::onFileClicked);
            recyclerView.setAdapter(fileAdapter);
        }
        upButton.setEnabled(directory.getParentFile() != null);
        setTitle(directory.getAbsolutePath());
    }

    private void onFileClicked(File file) {
        if (file.isDirectory()) {
            currentDirectory = file;
            loadFiles(currentDirectory);
            confirmButton.setEnabled(true);
        }
    }
}
