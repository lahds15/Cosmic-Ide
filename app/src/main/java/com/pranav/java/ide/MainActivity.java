package com.pranav.java.ide;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.googlecode.d2j.smali.BaksmaliCmd;
import com.pranav.java.ide.compiler.CompileTask;
import com.pranav.java.ide.ui.TreeViewDrawer;
import com.pranav.java.ide.ui.treeview.helper.TreeCreateNewFileContent;
import com.pranav.lib_android.code.disassembler.ClassFileDisassembler;
import com.pranav.lib_android.code.formatter.Formatter;
import com.pranav.lib_android.incremental.Indexer;
import com.pranav.lib_android.task.JavaBuilder;
import com.pranav.lib_android.util.ConcurrentUtil;
import com.pranav.lib_android.util.FileUtil;
import com.pranav.lib_android.util.ZipUtil;

import io.github.rosemoe.sora.langs.java.JavaLanguage;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public final class MainActivity extends AppCompatActivity {

    public CodeEditor editor;
    public DrawerLayout drawer;
    public JavaBuilder builder;

    private AlertDialog loadingDialog;
    private Thread runThread;

    public String currentWorkingFilePath;
    public Indexer indexer;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editor = findViewById(R.id.editor);
        drawer = findViewById(R.id.mDrawerLayout);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(false);

        ActionBarDrawerToggle toggle =
                new ActionBarDrawerToggle(
                        this, drawer, toolbar, R.string.open_drawer, R.string.close_drawer);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        editor.setTypefaceText(Typeface.MONOSPACE);
        editor.setEditorLanguage(new JavaLanguage());
        editor.setColorScheme(new SchemeDarcula());
        editor.setTextSize(12);

        try {
            indexer = new Indexer("editor");
        } catch (org.json.JSONException e) {
            dialog("JsonException", e.getMessage(), true);
        }
        if (indexer.notHas("currentFile")) {
            indexer.put("currentFile", FileUtil.getJavaDir() + "Main.java");
            indexer.flush();
        }

        currentWorkingFilePath = indexer.getString("currentFile");
        final File file = file(currentWorkingFilePath);

        if (file.exists()) {
            try {
                editor.setText(FileUtil.readFile(file));
            } catch (Exception e) {
                dialog("Cannot read file", getString(e), true);
            }
        } else {
            try {
                file.getParentFile().mkdirs();
                FileUtil.writeFile(file.getAbsolutePath(),
                        TreeCreateNewFileContent.BUILD_NEW_FILE_CONTENT("Main"));
                editor.setText(TreeCreateNewFileContent.BUILD_NEW_FILE_CONTENT("Main"));
            } catch (IOException e) {
                dialog("Cannot create file", getString(e), true);
            }
        }

        builder = new JavaBuilder(getApplicationContext(), getClassLoader());

        ConcurrentUtil.executeInBackground(
                () -> {
                    if (!file(FileUtil.getClasspathDir() + "android.jar").exists()) {
                        ZipUtil.unzipFromAssets(
                                MainActivity.this, "android.jar.zip", FileUtil.getClasspathDir());
                    }
                    File output = file(FileUtil.getClasspathDir() + "/core-lambda-stubs.jar");
                    if (!output.exists()
                            && getSharedPreferences("compiler_settings", Context.MODE_PRIVATE)
                                    .getString("javaVersion", "7.0")
                                    .equals("8.0")) {
                        try {
                            Files.write(
                                    ByteStreams.toByteArray(
                                            getAssets().open("core-lambda-stubs.jar")),
                                    output);
                        } catch (Exception e) {
                            showErr(getString(e));
                        }
                    }
                });
        /* Create Loading Dialog */
        buildLoadingDialog();

        /* Insert Fragment with TreeView into Drawer */
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.frameLayout, new TreeViewDrawer());
        fragmentTransaction.commit();

        findViewById(R.id.btn_disassemble).setOnClickListener(v -> disassemble());
        findViewById(R.id.btn_smali2java).setOnClickListener(v -> decompile());
        findViewById(R.id.btn_smali).setOnClickListener(v -> smali());
    }

    /* Build Loading Dialog - This dialog shows on code compilation */
    void buildLoadingDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(MainActivity.this);
        ViewGroup viewGroup = findViewById(android.R.id.content);
        View dialogView =
                getLayoutInflater().inflate(R.layout.compile_loading_dialog, viewGroup, false);
        builder.setView(dialogView);
        loadingDialog = builder.create();
        loadingDialog.setCancelable(false);
        loadingDialog.setCanceledOnTouchOutside(false);
    }

    /* To Change visible to user Stage TextView Text to actually compiling stage in Compile.java */
    void changeLoadingDialogBuildStage(String stage) {
        if (loadingDialog.isShowing()) {
            /* So, this method is also triggered from another thread (Compile.java)
             * We need to make sure that this code is executed on main thread */
            runOnUiThread(
                    () -> {
                        TextView stage_txt = loadingDialog.findViewById(R.id.stage_txt);
                        stage_txt.setText(stage);
                    });
        }
    }

    public void loadFileToEditor(String path) throws IOException {
        File newWorkingFile = new File(path);
        editor.setText(FileUtil.readFile(newWorkingFile));
        indexer.put("currentFile", path);
        currentWorkingFilePath = path;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.format_menu_button:
                Formatter formatter = new Formatter(editor.getText().toString());
                ConcurrentUtil.execute(() -> editor.setText(formatter.format()));
                break;

            case R.id.settings_menu_button:
                Intent intent = new Intent(MainActivity.this, SettingActivity.class);
                startActivity(intent);
                break;
            case R.id.run_menu_button:
                compile();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        editor.release();
        if (runThread != null && runThread.isAlive()) {
            runThread.interrupt();
        }
        super.onDestroy();
    }

    public void showErr(final String e) {
        Snackbar.make(
                        (LinearLayout) findViewById(R.id.container),
                        "An error occurred",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("Show error", (view) -> dialog("Failed...", e, true))
                .show();
    }

    public void compile() {
        loadingDialog.show(); // Show Loading Dialog
        runThread =
                new Thread(
                        new CompileTask(
                                MainActivity.this,
                                new CompileTask.CompilerListeners() {
                                    @Override
                                    public void OnCurrentBuildStageChanged(String stage) {
                                        changeLoadingDialogBuildStage(stage);
                                    }

                                    @Override
                                    public void OnSuccess() {
                                        loadingDialog.dismiss();
                                    }

                                    @Override
                                    public void OnFailed() {
                                        if (loadingDialog.isShowing()) {
                                            loadingDialog.dismiss();
                                        }
                                    }
                                }));
        runThread.start();
    }

    public void smali() {
        try {
            final String[] classes = getClassesFromDex();
            if (classes == null) return;
            listDialog(
                    "Select a class to extract source",
                    classes,
                    (d, pos) -> {
                        final String claz = classes[pos];
                        final String[] args =
                                new String[] {
                                    "-f",
                                    "-o",
                                    FileUtil.getBinDir().concat("smali/"),
                                    FileUtil.getBinDir().concat("classes.dex")
                                };
                        ConcurrentUtil.execute(() -> BaksmaliCmd.main(args));

                        CodeEditor edi = new CodeEditor(MainActivity.this);
                        edi.setTypefaceText(Typeface.MONOSPACE);
                        edi.setEditorLanguage(new JavaLanguage());
                        edi.setColorScheme(new SchemeDarcula());
                        edi.setTextSize(13);

                        File smaliFile =
                                file(
                                        FileUtil.getBinDir()
                                                + "smali/"
                                                + claz.replace(".", "/")
                                                + ".smali");

                        try {
                            edi.setText(
                                    formatSmali(
                                            FileUtil.readFile(smaliFile)));
                        } catch (IOException e) {
                            dialog("Cannot read file", getString(e), true);
                        }

                        final AlertDialog dialog =
                                new AlertDialog.Builder(MainActivity.this).setView(edi).create();
                        dialog.setCanceledOnTouchOutside(true);
                        dialog.show();
                    });
        } catch (Throwable e) {
            dialog("Failed to extract smali source", getString(e), true);
        }
    }

    public void decompile() {
        final String[] classes = getClassesFromDex();
        if (classes == null) return;
        listDialog(
                "Select a class to extract source",
                classes,
                (dialog, pos) -> {
                    final String claz = classes[pos].replace(".", "/");
                    String[] args = {
                        FileUtil.getBinDir()
                                + "classes/"
                                + claz
                                + // full class name
                                ".class",
                        "--extraclasspath",
                        FileUtil.getClasspathDir() + "android.jar",
                        "--outputdir",
                        FileUtil.getBinDir() + "cfr/"
                    };

                    ConcurrentUtil.execute(
                            () -> {
                                try {
                                    org.benf.cfr.reader.Main.main(args);
                                } catch (Exception e) {
                                    dialog("Failed to decompile...", getString(e), true);
                                }
                            });

                    final CodeEditor edi = new CodeEditor(MainActivity.this);
                    edi.setTypefaceText(Typeface.MONOSPACE);
                    edi.setEditorLanguage(new JavaLanguage());
                    edi.setColorScheme(new SchemeDarcula());
                    edi.setTextSize(12);

                    File decompiledFile = file(FileUtil.getBinDir() + "cfr/" + claz + ".java");

                    try {
                        edi.setText(FileUtil.readFile(decompiledFile));
                    } catch (IOException e) {
                        dialog("Cannot read file", getString(e), true);
                    }

                    final AlertDialog d =
                            new AlertDialog.Builder(MainActivity.this).setView(edi).create();
                    d.setCanceledOnTouchOutside(true);
                    d.show();
                });
    }

    public void disassemble() {
        final String[] classes = getClassesFromDex();
        if (classes == null) return;
        listDialog(
                "Select a class to disassemble",
                classes,
                (dialog, pos) -> {
                    final String claz = classes[pos].replace(".", "/");

                    final CodeEditor edi = new CodeEditor(MainActivity.this);
                    edi.setTypefaceText(Typeface.MONOSPACE);
                    edi.setEditorLanguage(new JavaLanguage());
                    edi.setColorScheme(new SchemeDarcula());
                    edi.setTextSize(12);

                    try {
                        final String disassembled =
                                new ClassFileDisassembler(
                                                FileUtil.getBinDir() + "classes/" + claz + ".class")
                                        .disassemble();

                        edi.setText(disassembled);

                        AlertDialog d =
                                new AlertDialog.Builder(MainActivity.this).setView(edi).create();
                        d.setCanceledOnTouchOutside(true);
                        d.show();
                    } catch (Throwable e) {
                        dialog("Failed to disassemble", getString(e), true);
                    }
                });
    }

    private String formatSmali(String in) {

        ArrayList<String> lines = new ArrayList<>(Arrays.asList(in.split("\n")));

        boolean insideMethod = false;

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            if (line.startsWith(".method")) insideMethod = true;

            if (line.startsWith(".end method")) insideMethod = false;

            if (insideMethod && !shouldSkip(line)) lines.set(i, line + "\n");
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            if (i != 0) result.append("\n");

            result.append(lines.get(i));
        }

        return result.toString();
    }

    private boolean shouldSkip(String smaliLine) {

        String[] ops = {".line", ":", ".prologue"};

        for (String op : ops) {
            if (smaliLine.trim().startsWith(op)) return true;
        }
        return false;
    }

    public void listDialog(String title, String[] items, DialogInterface.OnClickListener listener) {
        runOnUiThread(
                () -> {
                    /*
                     * @TheWolf:
                     * This method is executed on another
                     * Thread, so DialogBuilder must be (I didn't find other solutions)
                     * in runOnUiThread
                     */

                    new MaterialAlertDialogBuilder(MainActivity.this)
                            .setTitle(title)
                            .setItems(items, listener)
                            .create()
                            .show();
                });
    }

    public void dialog(String title, final String message, boolean copyButton) {
        final MaterialAlertDialogBuilder dialog =
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("GOT IT", null)
                        .setNegativeButton("CANCEL", null);
        if (copyButton)
            dialog.setNeutralButton(
                    "COPY",
                    (dialogInterface, i) -> {
                        ((ClipboardManager)
                                        getSystemService(getApplicationContext().CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("clipboard", message));
                    });
        dialog.create().show();
    }

    public String[] getClassesFromDex() {
        try {
            final File dex = new File(FileUtil.getBinDir().concat("classes.dex"));
            /* If the project doesn't seem to have been compiled yet, compile it */
            if (!dex.exists()) {
                compile();
            }
            final ArrayList<String> classes = new ArrayList<>();
            DexFile dexfile = DexFileFactory.loadDexFile(dex.getAbsolutePath(), Opcodes.forApi(26));
            for (ClassDef f : dexfile.getClasses().toArray(new ClassDef[0])) {
                String name = f.getType().replace("/", "."); // convert class name to standard form
                classes.add(name.substring(1, name.length() - 1));
            }
            return classes.toArray(new String[0]);
        } catch (Exception e) {
            dialog("Failed to get available classes in dex...", getString(e), true);
            return null;
        }
    }

    public File file(final String path) {
        return new File(path);
    }

    private String getString(final Throwable e) {
        return Log.getStackTraceString(e);
    }
}
