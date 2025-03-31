package com.thekstudio.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import java.io.*;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.*;
import com.thekstudio.pdfviewer.helpers.Orientation;

@DesignerComponent(
        version = 1,
        versionName = "1.0",
        description = "PDF Viewer extension to load and view PDF files. Developed by The K Studio.",
        iconName = "icon.png")

public class PDFViewer extends AndroidNonvisibleComponent {

    private PDFAdapter pdfAdapter;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private final SparseArray<Bitmap> pageBitmaps = new SparseArray<>();
    private ViewPager2 viewPager2;
    private String filePath = "";
    private int renderQuality = 100;
    private int totalPages = 0;
    private int backgroundColor = Color.WHITE;
    private boolean enablePageSnapper = false;
    private String pdfSourceType = "";
    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    public PDFViewer(ComponentContainer container) {
        super(container.$form());
    }

    @SimpleFunction(description = "Loads a PDF from the given file path and loads PDF View in the given component layout. " +
            "Supports loading from assets, storage, URL, or content URIs. " +
            "Specify 'test.pdf' for assets, '/storage/emulated/0/test.pdf' for storage, 'http://example.com/test.pdf' for URL, and 'content://...' for content URIs.")
    public void LoadPDF(String filePath, AndroidViewComponent component, Orientation orientation, boolean enableSnap) {

        ClosePDF();
        this.enablePageSnapper = enableSnap;
        this.filePath = filePath;

        if (filePath.startsWith("http")) {
            pdfSourceType = "url";
            downloadAndLoadPDF(filePath, component, orientation.toUnderlyingValue());
        } else if (filePath.startsWith("/")) {
            pdfSourceType = "storage";
            loadPDFFromStorage(filePath, component, orientation.toUnderlyingValue());
        } else if (filePath.startsWith("content://")) {
            pdfSourceType = "storage";
            loadPDFFromContentUri(filePath, component, orientation.toUnderlyingValue());
        } else {
            pdfSourceType = "assets";
            loadPDFFromAssets(filePath, component, orientation.toUnderlyingValue());
        }
    }

    // Load pdf from content URI
    private void loadPDFFromContentUri(String uriPath, AndroidViewComponent component, String orientation) {
        try {
            Uri uri = Uri.parse(uriPath);
            ParcelFileDescriptor descriptor = form.getContentResolver().openFileDescriptor(uri, "r");

            if (descriptor != null) {
                loadPDFWithDescriptor(descriptor, component, orientation);
            } else {
                OnError("Failed to open content URI.");
            }
        } catch (IOException e) {
            OnError("Failed to load PDF from content URI: " + e.getMessage());
        }
    }


    // Load PDF from device storage
    private void loadPDFFromStorage(String filePath, AndroidViewComponent component, String orientation) {
        try {
            ParcelFileDescriptor descriptor;
            if (filePath.startsWith("content://")) {
                descriptor = form.getContentResolver().openFileDescriptor(Uri.parse(filePath), "r");
            } else {
                File file = new File(filePath);
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }

            if (descriptor != null) {
                loadPDFWithDescriptor(descriptor, component, orientation);
            } else {
                OnError("Failed to open file.");
            }
        } catch (IOException e) {
            OnError("Failed to load PDF from storage: " + e.getMessage());
        }
    }



    // Load PDF from assets by copying it to a temporary file
    private void loadPDFFromAssets(String assetPath, AndroidViewComponent component, String orientation) {
        executorService.execute(() -> {
            try {
                // Create a temporary file in the cache directory
                File tempFile2 = new File(form.getCacheDir(), assetPath);
                if (!tempFile2.exists()) {
                    InputStream input = form.getAssets().open(assetPath);
                    FileOutputStream output = new FileOutputStream(tempFile2);
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                    }
                    input.close();
                    output.close();
                }

                // Load the copied file
                form.runOnUiThread(() -> loadPDFFromStorage(tempFile2.getAbsolutePath(), component, orientation));
            } catch (IOException e) {
                form.runOnUiThread(() -> OnError("Failed to load PDF: " + e.getMessage()));
            }
        });
    }


    // Download PDF from URL and load it
    private void downloadAndLoadPDF(String url, AndroidViewComponent component, String orientation) {
        executorService.execute(() -> {
            try {
                File tempFile = File.createTempFile("downloaded_pdf", ".pdf", form.getCacheDir());

                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setDoInput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
                connection.connect();

                int fileLength = connection.getContentLength();
                InputStream input = new BufferedInputStream(connection.getInputStream());
                FileOutputStream output = new FileOutputStream(tempFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    if (fileLength > 0) {
                        int progress = (int) ((totalBytesRead * 100) / fileLength);
                        int finalProgress = Math.min(progress, 100);
                        form.runOnUiThread(() -> DownloadProgress(finalProgress));
                    }
                }

                input.close();
                output.close();
                connection.disconnect();

                form.runOnUiThread(() -> loadPDFFromStorage(tempFile.getAbsolutePath(), component, orientation));
            } catch (IOException e) {
                form.runOnUiThread(() -> OnError("Failed to download PDF: " + e.getMessage()));
            }
        });
    }

    @SimpleEvent(description = "Triggered when download progress updates.")
    public void DownloadProgress(int progress) {
        EventDispatcher.dispatchEvent(this, "DownloadProgress", progress);
    }


    // Load PDF with given file descriptor
    private void loadPDFWithDescriptor(ParcelFileDescriptor descriptor, AndroidViewComponent component, String orientation) {
        try {
            pdfRenderer = new PdfRenderer(descriptor);
            totalPages = pdfRenderer.getPageCount();
            pageBitmaps.clear();

            View view = component.getView();
            if (view instanceof FrameLayout) {
                viewPager2 = new ViewPager2(view.getContext());
                ((FrameLayout) view).removeAllViews();
                ((FrameLayout) view).addView(viewPager2);

                // Set orientation
                viewPager2.setOrientation("vertical".equalsIgnoreCase(orientation) ?
                        ViewPager2.ORIENTATION_VERTICAL : ViewPager2.ORIENTATION_HORIZONTAL);

                pdfAdapter = new PDFAdapter(totalPages, renderQuality, pdfRenderer, pageBitmaps, executorService, viewPager2, this, backgroundColor);
                viewPager2.setAdapter(pdfAdapter);
                applySnapBehavior();

                String pdfFileName = GetPDFFileName();
                PDFLoaded(totalPages, pdfFileName);

            } else {
                OnError("Invalid component. Use a View component such as Vertical Arrangement.");
            }
        } catch (IOException e) {
            OnError("Error loading PDF: " + e.getMessage());
        }
    }


    private void applySnapBehavior() {
        if (viewPager2 == null) return;

        View child = viewPager2.getChildAt(0);
        if (child instanceof RecyclerView) {
            RecyclerView recyclerView = getRecyclerView((RecyclerView) child);
            recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                        if (layoutManager != null) {
                            int getPosition = layoutManager.findFirstCompletelyVisibleItemPosition();
                            if (getPosition != RecyclerView.NO_POSITION) {
                                PageChanged(getPosition + 1);
                            }
                        }
                    }
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int getPosition = layoutManager.findFirstVisibleItemPosition();
                        if (getPosition != RecyclerView.NO_POSITION) {
                            PageChanged(getPosition + 1);
                        }
                    }
                }
            });
        }
    }

    private RecyclerView getRecyclerView(RecyclerView child) {
        child.setOverScrollMode(RecyclerView.OVER_SCROLL_NEVER);
        if (!enablePageSnapper) {
            child.setOnFlingListener(null);
        }
        return child;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_COLOR, defaultValue = Component.DEFAULT_VALUE_COLOR_WHITE)
    @SimpleProperty(description = "Specifies the background color of the PDF Viewer.")
    public void BackgroundColor(int color) {
        this.backgroundColor = color;
        if (pdfAdapter != null) {
            pdfAdapter.notifyDataSetChanged();
        }
    }

    @SimpleFunction(description = "Closes the currently opened PDF.")
    public void ClosePDF() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }
            executorService = Executors.newSingleThreadExecutor();

            if (pdfAdapter != null) {
                pdfAdapter.close();
            }
            if (fileDescriptor != null) {
                fileDescriptor.close();
                fileDescriptor = null;
            }
            pageBitmaps.clear();
            totalPages = 0;
            if (viewPager2 != null) {
                viewPager2.setAdapter(null);
                viewPager2 = null;
            }
            // Check and delete the temp file if it exists in cache
            File tempFile = new File(form.getCacheDir(), "downloaded_pdf.pdf");
            if (tempFile.exists()) {
                tempFile.delete();
            }

        } catch (IOException e) {
            OnError("Error closing PDF: " + e.getMessage());
        }
    }

    @SimpleEvent(description = "Triggered to indicate rendering progress.")
    public void RenderProgress(int progress) {
        EventDispatcher.dispatchEvent(this, "RenderProgress", progress);
    }

    @SimpleEvent(description = "Triggered when a PDF is successfully loaded with the total number of pages.")
    public void PDFLoaded(int totalPages, String pdfFileName) {
        EventDispatcher.dispatchEvent(this, "PDFLoaded", totalPages, pdfFileName);
    }

    @SimpleEvent(description = "Triggered when the page changes.")
    public void PageChanged(int currentPage) {
        EventDispatcher.dispatchEvent(this, "PageChanged", currentPage);
    }

    @SimpleFunction(description = "Returns the total number of pages in the PDF.")
    public int GetTotalPages() {
        return totalPages;
    }

    @SimpleFunction(description = "Returns the currently visible page index.")
    public int GetCurrentPage() {
        if (viewPager2 != null) {
            return viewPager2.getCurrentItem() + 1;
        }
        return 1;
    }

    @SimpleFunction(description = "Jumps to a specific page in the PDF.")
    public void GoToPage(int pageIndex) {
        if (viewPager2 == null || pdfRenderer == null) {
            OnError("PDF not loaded.");
            return;
        }
        if (pageIndex < 1 || pageIndex > totalPages) {
            OnError("Invalid page index: " + pageIndex);
            return;
        }
        viewPager2.setCurrentItem(pageIndex - 1);
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_INTEGER, defaultValue = "100")
    @SimpleProperty(description = "Sets the rendering quality between 30 to 500. Default is 100. Set lower quality for better performance.")
    public void RenderQuality(int quality) {
        this.renderQuality = Math.max(30, Math.min(quality, 500));
    }

    @SimpleEvent(description = "Triggered when an error occurs.")
    public void OnError(String errorMessage) {
        EventDispatcher.dispatchEvent(this, "OnError", errorMessage);
    }

    @SimpleFunction(description = "Returns the app's specific directory (ASD).")
    public String GetASD() {
        try {
            File appSpecificDir = form.getExternalFilesDir(null);
            if (appSpecificDir != null) {
                return appSpecificDir.getAbsolutePath();
            } else {
                return "External storage not available";
            }
        } catch (Exception e) {
            OnError(e.getMessage());
            return null;
        }
    }


    @SimpleFunction(description = "Returns the file name of the currently opened PDF.")
    public String GetPDFFileName() {
        if (filePath == null || filePath.isEmpty()) {
            return "Unknown";
        }

        try {
            Uri uri = Uri.parse(filePath);
            String fileName;

            switch (pdfSourceType) {
                case "assets":
                    fileName = filePath;
                    break;
                case "url":
                    fileName = uri.getLastPathSegment();
                    break;
                case "storage":
                default:
                    fileName = getFileName(uri);
                    break;
            }

            if (fileName == null || fileName.isEmpty()) {
                fileName = "Unknown";
            }

            // Remove .pdf extension if present
            if (fileName.toLowerCase().endsWith(".pdf")) {
                fileName = fileName.substring(0, fileName.length() - 4);
            }

            return fileName;
        } catch (Exception e) {
            OnError("Error getting PDF file name: " + e.getMessage());
            return "Unknown";
        }
    }

    // Improved getFileName method
    private String getFileName(Uri uri) {
        String fileName = null;

        if (filePath.startsWith("content://")) {
            try (Cursor cursor = form.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        fileName = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                OnError("Error retrieving file name from content URI: " + e.getMessage());
            }
        } else {
            fileName = new File(filePath).getName();
        }

        if (fileName == null || fileName.isEmpty()) {
            fileName = "Unknown";
        }

        if (fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        return fileName;
    }




    @SimpleFunction(description = "Saves the PDF page to the given file path as an image.")
    public void SavePage(final int pageIndex, final String outputPath) {
        int adjustedIndex = pageIndex - 1; // Convert 1-based index to 0-based

        if (adjustedIndex < 0 || adjustedIndex >= pageBitmaps.size() || pageBitmaps.get(adjustedIndex) == null) {
            OnError("Page not rendered yet or invalid page index: " + pageIndex);
            return;
        }

        // Create a separate executor for each save operation
        ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

        saveExecutor.execute(() -> {
            Bitmap bitmap = pageBitmaps.get(adjustedIndex);
            File outputFile = new File(outputPath);

            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.flush();

                form.runOnUiThread(() -> PageSaved(pageIndex, outputPath));

            } catch (IOException e) {
                form.runOnUiThread(() -> OnError("Error saving page: " + e.getMessage()));
            } finally {
                saveExecutor.shutdown();
            }
        });
    }


    @SimpleEvent(description = "Triggered when a page is successfully saved. Return saved page file path.")
    public void PageSaved(int pageIndex, String filePath) {
        EventDispatcher.dispatchEvent(this, "PageSaved", pageIndex, filePath);
    }


    @SimpleFunction(description = "Saves all rendered PDF pages to the given directory.")
    public void SaveAllPages(String outputDir) {
        saveNextPage(0, outputDir, Executors.newSingleThreadExecutor());
    }

    private void saveNextPage(int index, String outputDir, ExecutorService saveExecutor2) {
        if (index >= pageBitmaps.size()) {
            saveExecutor2.shutdown();
            form.runOnUiThread(() -> AllPagesSaved(outputDir));
            return;
        }

        Bitmap bitmap;
        synchronized (pageBitmaps) {
            bitmap = pageBitmaps.get(index);
        }

        if (bitmap == null) {
            form.runOnUiThread(() -> OnError("Page " + (index + 1) + " not available."));
            saveNextPage(index + 1, outputDir, saveExecutor2);
            return;
        }

        String outputPath = outputDir + "/page_" + (index + 1) + ".png";

        saveExecutor2.execute(() -> {
            boolean success = saveBitmapToFile(bitmap, outputPath);

            form.runOnUiThread(() -> {
                if (success) {
                    PageSaved(index + 1, outputPath);
                } else {
                    OnError("Error saving page " + (index + 1));
                }
                saveNextPage(index + 1, outputDir, saveExecutor2);
            });
        });
    }


    private boolean saveBitmapToFile(Bitmap bitmap, String outputPath) {
        File outputFile = new File(outputPath);
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @SimpleEvent(description = "Triggered when all pages are successfully saved. Returns the saved pages directory path.")
    public void AllPagesSaved(String outputDir) {
        EventDispatcher.dispatchEvent(this, "AllPagesSaved", outputDir);
    }

    @SimpleFunction(description = "Returns the total number of pages rendered.")
    public int GetRenderedPageCount() {
        return pageBitmaps.size();
    }
}
