package dk.madslee.imageSequence;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.os.AsyncTask;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.RejectedExecutionException;
import pl.igorczapski.utils.IOUtils;


public class RCTImageSequenceView extends ImageView {
	private static final int DEFAULT_FPS = 24;

	private Integer framesPerSecond = DEFAULT_FPS;
	private ArrayList<AsyncTask> activeTasks;
	private HashMap<Integer, Bitmap> bitmaps;
	private RCTResourceDrawableIdHelper resourceDrawableIdHelper;
	private Integer desiredWidth = -1;
	private Integer desiredHeight = -1;
	/**
	 * Map for storing uri to decoded image and its index in the bitmaps map.
	 * This allows to avoid decoding repeated bitmaps.
	 */
	private HashMap<String, Integer> bitmapIndexes;
	/**
	 * Shared memory for decoding images. Shared between tasks, so they have to be executed in serial order.
	 */
	byte[] tempStorage;

	public RCTImageSequenceView(Context context) {
		super(context);
		resourceDrawableIdHelper = new RCTResourceDrawableIdHelper();
	}

	private class DownloadImageTask extends AsyncTask<String, Void, Bitmap> {
		private final Integer index;
		private final String uri;
		private final Context context;
		private final Integer desiredWidth;
		private final Integer desiredHeight;

		public DownloadImageTask(Integer index, String uri, Context context, Integer width, Integer height) {
			this.index = index;
			this.uri = uri;
			this.context = context;
			this.desiredWidth = width;
			this.desiredHeight = height;
		}

		@Override
		protected Bitmap doInBackground(String... params) {
			if (bitmapIndexes.containsKey(uri)) {
				return bitmaps.get(bitmapIndexes.get(uri));
			}
			if (this.uri.startsWith("http")) {
				return this.loadBitmapByExternalURL(this.uri);
			}
			return this.loadBitmapByLocalResource(this.uri);
		}

		private Bitmap loadBitmapByLocalResource(String uri) {
			if (this.desiredWidth > 0 && this.desiredHeight > 0) {
				BitmapFactory.Options opts = new BitmapFactory.Options();
				opts.inScaled = false;
				opts.inTempStorage = tempStorage;
				try {
					if (context == null || isCancelled()) {
						return null;
					}
					int resDrawableId = resourceDrawableIdHelper.getResourceDrawableId(context, uri);
					if (resDrawableId != 0) {
						return BitmapFactory.decodeResource(context.getResources(), resDrawableId, opts);
					}
					if (new File(uri).exists()) {
						return BitmapFactory.decodeFile(uri, opts);
					}
				}
				catch (Exception ex) {
					Log.e("RCTImageSequence", "Cannot decode bitmap from file.");
					ex.printStackTrace();
				}
				return null;
			}
			else {
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inTempStorage = tempStorage;
				Bitmap bitmap = BitmapFactory.decodeResource(this.context.getResources(),
						resourceDrawableIdHelper.getResourceDrawableId(this.context, uri), options);
				return bitmap;
			}
		}

		private Bitmap loadBitmapByExternalURL(String uri) {
			Bitmap bitmap = null;
			InputStream in = null;
			BitmapFactory.Options opts = new BitmapFactory.Options();
			opts.inScaled = false;
			opts.inJustDecodeBounds = false;
			opts.inTempStorage = tempStorage;
			try {
				in = new URL(uri).openStream();
				bitmap = BitmapFactory.decodeStream(in, null, opts);
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				IOUtils.closeQuietly(in);
			}
			if (bitmap != null && this.desiredWidth > 0 && this.desiredHeight > 0) {
				return Bitmap.createScaledBitmap(bitmap, this.desiredWidth, this.desiredHeight, false);
			}
			return bitmap;
		}

		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (context != null && !isCancelled()) {
				onTaskCompleted(this, index, bitmap, uri);
			}
		}
	}

	private void onTaskCompleted(DownloadImageTask downloadImageTask, Integer index, Bitmap bitmap, String uri) {
		if (index == 0) {
			// first image should be displayed as soon as possible.
			this.setImageBitmap(bitmap);
		}

		bitmapIndexes.put(uri, index);
		bitmaps.put(index, bitmap);
		activeTasks.remove(downloadImageTask);

		if (activeTasks.isEmpty()) {
			setupAnimationDrawable();
		}
	}

	public void setImages(ArrayList<String> uris) {
		if (isLoading()) {
			// cancel ongoing tasks (if still loading previous images)
			for (int index = 0; index < activeTasks.size(); index++) {
				activeTasks.get(index).cancel(true);
			}
		}
		activeTasks = new ArrayList<>(uris.size());
		bitmaps = new HashMap<>(uris.size());
		bitmapIndexes = new HashMap<>();
		//16K used by Bitmap by default, but this storage is allocated once and shared between tasks (they need to be executed within SERIAL_EXECUTOR)
		tempStorage = new byte[16 * 1024];
		for (int index = 0; index < uris.size(); index++) {
			DownloadImageTask task =
					new DownloadImageTask(index, uris.get(index), getContext(), this.desiredWidth, this.desiredHeight);
			activeTasks.add(task);
			try {
				task.execute();
			}
			catch (RejectedExecutionException e) {
				Log.e("RCTImageSequenceView", "DownloadImageTask failed" + e.getMessage());
				break;
			}
		}
	}

	public void setFramesPerSecond(Integer framesPerSecond) {
		this.framesPerSecond = framesPerSecond;

		// updating frames per second, results in building a new AnimationDrawable (because we cant alter frame duration)
		if (isLoaded()) {
			setupAnimationDrawable();
		}
	}

	public void setDesiredSize(Integer width, Integer height) {
		this.desiredWidth = width;
		this.desiredHeight = height;
	}

	private boolean isLoaded() {
		return !isLoading() && bitmaps != null && !bitmaps.isEmpty();
	}

	private boolean isLoading() {
		return activeTasks != null && !activeTasks.isEmpty();
	}

	private void setupAnimationDrawable() {
		AnimationDrawable animationDrawable = new AnimationDrawable();
		for (int index = 0; index < bitmaps.size(); index++) {
			BitmapDrawable drawable = new BitmapDrawable(this.getResources(), bitmaps.get(index));
			animationDrawable.addFrame(drawable, 1000 / framesPerSecond);
		}
		tempStorage = null;
		animationDrawable.setOneShot(false);
		animationDrawable.start();
		this.setImageDrawable(animationDrawable);
	}
}