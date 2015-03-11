package com.photowall.galis;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.FragmentActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;


public class MainActivity extends FragmentActivity {

    private static final int FADE_IN_TIME = 200;
    private int mMaxMemory;
    private GridView mShowPhotoGridView;
    private int mGridItemWidth;
    private LinkedList<String> mPhotos;
    private LruCache<String, BitmapDrawable> mMemoryCache;
    private Set<BitmapLoadTask> mBitmapLoadTaskSet;
    private PhotoAdapter mPhotoAdapter;
    private Bitmap mLoadingBitmap;
    private boolean mFadeInBitmap = true;
    private Resources mResources;

    private final Object mPauseWorkLock = new Object();
    private Set<SoftReference<Bitmap>> mReusableBitmaps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMaxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024 / 4);

        if (BuildConfig.DEBUG) {
            System.out.println(mMaxMemory + "");
        }
        mMemoryCache = new LruCache<String, BitmapDrawable>(mMaxMemory) {
            @Override
            protected void entryRemoved(boolean evicted, String key, BitmapDrawable oldValue, BitmapDrawable newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);

                if (RecyclingBitmapDrawable.class.isInstance(oldValue)) {
                    // The removed entry is a recycling drawable, so notify it
                    // that it has been removed from the memory cache
                    ((RecyclingBitmapDrawable) oldValue).setIsCached(false);
                    System.out.println("RecyclingBitmapDrawable");
                } else {
                    // The removed entry is a standard BitmapDrawable

                    if (Utils.hasHoneycomb()) {
                        // We're running on Honeycomb or later, so add the bitmap
                        // to a SoftReference set for possible use with inBitmap later
                        mReusableBitmaps.add(new SoftReference<>(oldValue.getBitmap()));
                        System.out.println("StandardBitmapDrawable");
                    }
                }
            }

            @Override
            protected BitmapDrawable create(String key) {
                return super.create(key);
            }

            @Override
            protected int sizeOf(String key, BitmapDrawable bitmapDrawable) {
                final int bitmapSize = getBitmapSize(bitmapDrawable) / 1024;
                return bitmapSize == 0 ? 1 : bitmapSize;
            }
        };


        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        mGridItemWidth = (int) ((displayMetrics.widthPixels - 2 * 5 * displayMetrics.density) / 3);

        mBitmapLoadTaskSet = new LinkedHashSet<>();
        mShowPhotoGridView = (GridView) findViewById(R.id.showPhotoGridView);
        mShowPhotoGridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {


            }
        });
        mPhotoAdapter = new PhotoAdapter();
        mShowPhotoGridView.setAdapter(mPhotoAdapter);
        mLoadingBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.empty_photo);
        mReusableBitmaps = Collections.synchronizedSet(new HashSet<SoftReference<Bitmap>>());
        initAlbums();
    }

    /**
     * Get the size in bytes of a bitmap in a BitmapDrawable. Note that from Android 4.4 (KitKat)
     * onward this returns the allocated memory size of the bitmap which can be larger than the
     * actual bitmap data byte count (in the case it was re-used).
     *
     * @param value
     * @return size in bytes
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static int getBitmapSize(BitmapDrawable value) {
        Bitmap bitmap = value.getBitmap();

        // From KitKat onward use getAllocationByteCount() as allocated bytes can potentially be
        // larger than bitmap byte count.
        if (Utils.hasKitKat()) {
            return bitmap.getAllocationByteCount();
        }

        if (Utils.hasHoneycombMR1()) {
            return bitmap.getByteCount();
        }

        // Pre HC-MR1
        return bitmap.getRowBytes() * bitmap.getHeight();
    }

    /**
     * Returns true if the current work has been canceled or if there was no work in
     * progress on this image view.
     * Returns false if the work in progress deals with the same data. The work is not
     * stopped in that case.
     */
    public static boolean cancelPotentialWork(String data, ImageView imageView) {
        final BitmapLoadTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
        if (bitmapWorkerTask != null) {
            final String bitmapData = bitmapWorkerTask.imageUrl;
            if (bitmapData == null || !bitmapData.equals(data)) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    /**
     * @param imageView Any imageView
     * @return Retrieve the currently active work task (if any) associated with this imageView.
     * null if there is no such task.
     */
    private static BitmapLoadTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapLoadTask();
            }
        }
        return null;
    }


    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initAlbums() {

        mPhotos = new LinkedList<>();
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA}, null, null, null);
        cursor.moveToFirst();
        while (cursor.moveToNext()) {
            String path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
            mPhotos.add(path);
        }
        mPhotoAdapter.notifyDataSetChanged();
    }


    class BitmapLoadTask extends AsyncTask<Void, Void, BitmapDrawable> {

        private String imageUrl;
        private WeakReference<ImageView> referImageView;

        public BitmapLoadTask(String url, ImageView imageView) {
            imageUrl = url;
            referImageView = new WeakReference<>(imageView);
        }

        @Override
        protected BitmapDrawable doInBackground(Void... params) {

            Bitmap bitmap = loadNativeFile(imageUrl, mGridItemWidth, mGridItemWidth);
            if (bitmap == null) {
                if (BuildConfig.DEBUG) {
                    System.out.println(imageUrl);
                    return new BitmapDrawable(mResources,mLoadingBitmap);
                }
            }

            BitmapDrawable drawable = null;
            if (bitmap != null) {
                if (Utils.hasHoneycomb()) {
                    // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                    drawable = new BitmapDrawable(mResources, bitmap);
                } else {
                    // Running on Gingerbread or older, so wrap in a RecyclingBitmapDrawable
                    // which will recycle automagically
                    drawable = new RecyclingBitmapDrawable(mResources, bitmap);
                }
                mMemoryCache.put(convertToHexKey(imageUrl), drawable);
            }

            return drawable;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(BitmapDrawable bitmapDrawable) {
            super.onPostExecute(bitmapDrawable);
            Drawable bgDrawble = referImageView.get().getDrawable();
            if (bgDrawble instanceof AsyncDrawable) {
                BitmapLoadTask task = ((AsyncDrawable) bgDrawble).getBitmapLoadTask();
                if (this == task) {
                    setImageDrawable(referImageView.get(), bitmapDrawable);
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }
    }

    /**
     * A custom Drawable that will be attached to the imageView while the work is in progress.
     * Contains a reference to the actual worker task, so that it can be stopped if a new binding is
     * required, and makes sure that only the last started worker process can bind its result,
     * independently of the finish order.
     */
    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapLoadTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, BitmapLoadTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        public BitmapLoadTask getBitmapLoadTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    class PhotoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mPhotos == null ? 0 : mPhotos.size();
        }

        @Override
        public Object getItem(int position) {
            return mPhotos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            RecyclingImageView imageView;
            if (convertView == null) {
                imageView = new RecyclingImageView(parent.getContext());
                imageView.setLayoutParams(new AbsListView.LayoutParams(mGridItemWidth, mGridItemWidth));
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                imageView = (RecyclingImageView) convertView;
            }

            BitmapDrawable bpDrawable = mMemoryCache.get(convertToHexKey(mPhotos.get(position)));
            String imageUrl = mPhotos.get(position);
            if (bpDrawable != null) {
                imageView.setImageDrawable(bpDrawable);
            } else if (cancelPotentialWork(imageUrl, imageView)) {
                BitmapLoadTask task = new BitmapLoadTask(imageUrl, imageView);
                AsyncDrawable asynDrawable = new AsyncDrawable(getResources(), mLoadingBitmap, task);
                task.executeOnExecutor(AsyncTask.DUAL_THREAD_EXECUTOR);
                imageView.setImageDrawable(asynDrawable);
            }
            return imageView;
        }
    }

    /**
     * 加载本地图片
     *
     * @param path
     * @param width
     * @param height
     * @return
     */
    private Bitmap loadNativeFile(String path, int width, int height) {

        File image = new File(path);
        BitmapFactory.Options options = new BitmapFactory.Options();

        options.inJustDecodeBounds = true;
        if (!image.exists()) {
            throw new NullPointerException(path + "is not exist!!");
        }

        FileDescriptor fd = null;
        try {
            fd = new FileInputStream(image).getFD();
        } catch (IOException e) {
            e.printStackTrace();
        }
        BitmapFactory.decodeFileDescriptor(fd,null, options);


        options.inSampleSize = calculateInSampleSize(options, width, height);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;


        addInBitmapOptions(options);
        return BitmapFactory.decodeFileDescriptor(fd, null, options);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void addInBitmapOptions(BitmapFactory.Options options) {
        //BEGIN_INCLUDE(add_bitmap_options)
        // inBitmap only works with mutable bitmaps so force the decoder to
        // return mutable bitmaps.
        options.inMutable = true;

        // Try and find a bitmap to use for inBitmap
        Bitmap inBitmap = getBitmapFromReusableSet(options);

        if (inBitmap != null) {
            options.inBitmap = inBitmap;
        }
        //END_INCLUDE(add_bitmap_options)
    }

    /**
     * @param options - BitmapFactory.Options with out* options populated
     * @return Bitmap that case be used for inBitmap
     */
    protected Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
        //BEGIN_INCLUDE(get_bitmap_from_reusable_set)
        Bitmap bitmap = null;

        if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
            synchronized (mReusableBitmaps) {
                final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
                Bitmap item;

                while (iterator.hasNext()) {
                    item = iterator.next().get();

                    if (null != item && item.isMutable()) {
                        // Check to see it the item can be used for inBitmap
                        if (canUseForInBitmap(item, options)) {
                            bitmap = item;
                            Log.e("Reuse!","reuse");
                            // Remove from reusable set so it can't be used again
                            iterator.remove();
                            break;
                        }
                    } else {
                        // Remove from the set if the reference has been cleared.
                        iterator.remove();
                    }
                }
            }
        }

        return bitmap;
        //END_INCLUDE(get_bitmap_from_reusable_set)
    }

    public static int calculateInSampleSize(BitmapFactory.Options options,
                                            int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        while (options.outHeight / inSampleSize > reqHeight && options.outWidth / inSampleSize > reqWidth) {
            inSampleSize *= 2;
        }
        return inSampleSize;
    }

    private void setImageDrawable(ImageView imageView, Drawable drawable) {
        if (mFadeInBitmap) {
            // Transition drawable with a transparent drawable and the final drawable
            final TransitionDrawable td =
                    new TransitionDrawable(new Drawable[]{
                            new ColorDrawable(android.R.color.transparent),
                            drawable
                    });
            // Set background to loading bitmap
            imageView.setBackgroundDrawable(
                    new BitmapDrawable(getResources(), mLoadingBitmap));

            imageView.setImageDrawable(td);
            td.startTransition(FADE_IN_TIME);
        } else {
            imageView.setImageDrawable(drawable);
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static boolean canUseForInBitmap(
            Bitmap candidate, BitmapFactory.Options targetOptions) {
//        int width = targetOptions.outWidth / targetOptions.inSampleSize;
//        int height = targetOptions.outHeight / targetOptions.inSampleSize;
//
//        // Returns true if "candidate" can be used for inBitmap re-use with
//        // "targetOptions".
//        return candidate.getWidth() == width && candidate.getHeight() == height;
        //BEGIN_INCLUDE(can_use_for_inbitmap)
        if (!Utils.hasKitKat()) {
            // On earlier versions, the dimensions must match exactly and the inSampleSize must be 1
            return candidate.getWidth() == targetOptions.outWidth
                    && candidate.getHeight() == targetOptions.outHeight
                    && targetOptions.inSampleSize == 1;
        }

        // From Android 4.4 (KitKat) onward we can re-use if the byte size of the new bitmap
        // is smaller than the reusable bitmap candidate allocation byte count.
        int width = targetOptions.outWidth / targetOptions.inSampleSize;
        int height = targetOptions.outHeight / targetOptions.inSampleSize;
        int byteCount = width * height * getBytesPerPixel(candidate.getConfig());
        return byteCount <= candidate.getAllocationByteCount();
        //END_INCLUDE(can_use_for_inbitmap)
    }

    /**
     * Return the byte usage per pixel of a bitmap based on its configuration.
     * @param config The bitmap configuration.
     * @return The byte usage per pixel.
     */
    private static int getBytesPerPixel(Bitmap.Config config) {
        if (config == Bitmap.Config.ARGB_8888) {
            return 4;
        } else if (config == Bitmap.Config.RGB_565) {
            return 2;
        } else if (config == Bitmap.Config.ARGB_4444) {
            return 2;
        } else if (config == Bitmap.Config.ALPHA_8) {
            return 1;
        }
        return 1;
    }
    private String convertToHexKey(String key) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(key.getBytes());
            return byteToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String byteToHexString(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            stringBuilder.append(hex.length() == 1 ? "0" : hex);
        }
        return stringBuilder.toString();
    }
}
