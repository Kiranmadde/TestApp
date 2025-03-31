package com.thekstudio.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfRenderer;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.jsibbold.zoomage.ZoomageView;
import java.util.concurrent.ExecutorService;

public class PDFAdapter extends RecyclerView.Adapter<PDFAdapter.PDFViewHolder> {
    private final int totalPages;
    private final int renderQuality;
    private final PdfRenderer pdfRenderer;
    private final SparseArray<Bitmap> pageBitmaps;
    private final ExecutorService executorService;
    private final ViewPager2 viewPager2;
    private final PDFViewer pdfViewer;
    private final int backgroundColor;
    private boolean isClosing = false;

    public PDFAdapter(int totalPages, int renderQuality, PdfRenderer pdfRenderer,
                      SparseArray<Bitmap> pageBitmaps, ExecutorService executorService,
                      ViewPager2 viewPager2, PDFViewer pdfViewer, int backgroundColor) {
        this.totalPages = totalPages;
        this.renderQuality = renderQuality;
        this.pdfRenderer = pdfRenderer;
        this.pageBitmaps = pageBitmaps;
        this.executorService = executorService;
        this.viewPager2 = viewPager2;
        this.pdfViewer = pdfViewer;
        this.backgroundColor = backgroundColor;
        preloadPages();
    }

    private void preloadPages() {
        for (int i = 0; i < totalPages; i++) {
            final int pageIndex = i;
            executorService.execute(() -> {
                if (isClosing) return;
                synchronized (pageBitmaps) {
                    if (pageBitmaps.get(pageIndex) != null) return;
                }

                Bitmap renderedBitmap = renderPage(pageIndex);
                if (renderedBitmap != null) {
                    synchronized (pageBitmaps) {
                        pageBitmaps.put(pageIndex, renderedBitmap);
                    }

                    viewPager2.post(() -> {
                        pdfViewer.RenderProgress(Math.min((pageBitmaps.size() * 100) / totalPages, 100));
                        notifyItemChanged(pageIndex);
                    });
                }
            });
        }
    }

    @NonNull
    @Override
    public PDFViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ZoomageView zoomageView = new ZoomageView(parent.getContext());
        zoomageView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        zoomageView.setBackgroundColor(backgroundColor);
        return new PDFViewHolder(zoomageView);
    }


    @Override
    public void onBindViewHolder(@NonNull PDFViewHolder holder, int position) {
        Bitmap cachedBitmap = pageBitmaps.get(position);
        if (cachedBitmap != null) {
            holder.zoomageView.setImageBitmap(cachedBitmap);
        }

        // Disable over-scrolling
        holder.zoomageView.setRestrictBounds(true);
        holder.zoomageView.setDoubleTapToZoomScaleFactor(2);

        // Disable ViewPager2 swipe when zoomed in
        holder.zoomageView.setOnTouchListener((ignoredView, ignoredEvent) -> {
            float scale = holder.zoomageView.getScaleX();
            viewPager2.setUserInputEnabled(scale <= 1.0f);
            return false;
        });
    }

    Bitmap renderPage(int position) {
        if (isClosing) return null;
        try (PdfRenderer.Page page = pdfRenderer.openPage(position)) {
            int width = page.getWidth() * renderQuality / 100;
            int height = page.getHeight() * renderQuality / 100;

            Bitmap renderedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(renderedBitmap);
            canvas.drawColor(backgroundColor);
            page.render(renderedBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            return renderedBitmap;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return totalPages;
    }

    public static class PDFViewHolder extends RecyclerView.ViewHolder {
        ZoomageView zoomageView;

        public PDFViewHolder(@NonNull View itemView) {
            super(itemView);
            zoomageView = (ZoomageView) itemView;
        }
    }

    public void close() {
        isClosing = true;
        synchronized (pageBitmaps) {
            pageBitmaps.clear();
        }
        try {
            pdfRenderer.close();
        } catch (Exception ignored) {
        }
    }
}

