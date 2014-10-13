package com.mantz_it.rfanalyzer;

import android.graphics.Canvas;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Analyzer Processing Loop</h1>
 *
 * Module:      AnalyzerProcessingLoop.java
 * Description: This Thread will fetch samples from the incoming queue, do the signal processing
 *              (fft) and then draw the result on the AnalyzerSurface at a fixed frame rate.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class AnalyzerProcessingLoop extends Thread {
	private int fftSize = 1024;				// Size of the FFT
	private int sampleRate = 0;				// Sample Rate of the incoming samples
	private long basebandFrequency = 0;		// Center Frequency of the incoming samples
	private int frameRate = 1;				// Frames per Second
	private double load = 0;				// Time_for_processing_and_drawing / Time_per_Frame
	private boolean stopRequested = true;	// Will stop the thread when set to true
	private static final String logtag = "AnalyzerProcessingLoop";

	private AnalyzerSurface view;
	private FFT fftBlock = null;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;		// queue that delivers sample packets
	private ArrayBlockingQueue<SamplePacket> returnQueue = null;	// queue to return unused buffers

	/**
	 * Constructor. Will initialize the member attributes.
	 *
	 * @param view			reference to the AnalyzerSurface for drawing
	 * @param sampleRate	sampleRate that was used to record the samples
	 * @param frameRate		fixed frame rate at which the fft should be drawn
	 */
	public AnalyzerProcessingLoop(AnalyzerSurface view, int sampleRate, int frameRate,
				ArrayBlockingQueue<SamplePacket> inputQueue, ArrayBlockingQueue<SamplePacket> returnQueue) {
		this.view = view;
		this.sampleRate = sampleRate;
		this.frameRate = frameRate;
		this.fftBlock = new FFT(fftSize);
		this.inputQueue = inputQueue;
		this.returnQueue = returnQueue;
	}

	public int getFrameRate() {
		return frameRate;
	}

	public void setFrameRate(int frameRate) {
		this.frameRate = frameRate;
	}

	public void stopProcessing() {
		this.stopRequested = true;
	}

	public int getFftSize() { return fftSize; }

	public int getSampleRate() { return sampleRate; }

	public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

	public long getBasebandFrequency() { return basebandFrequency; }

	public void setBasebandFrequency(long basebandFrequency) {
		this.basebandFrequency = basebandFrequency;
	}

	public void setFftSize(int fftSize) {
		int order = (int)(Math.log(fftSize) / Math.log(2));

		// Check if fftSize is a power of 2
		if(fftSize != (1<<order))
			throw new IllegalArgumentException("FFT size must be power of 2");
		this.fftSize = fftSize;
		this.fftBlock = new FFT(fftSize);
	}

	/**
	 * Will start the processing loop
	 */
	@Override
	public void start() {
		this.stopRequested = false;
		super.start();
	}

	/**
	 * Will set the stopRequested flag so that the processing loop will terminate
	 */
	public void stopLoop() {
		this.stopRequested = true;
	}

	/**
	 * @return true if loop is running; false if not.
	 */
	public boolean isRunning() {
		return !stopRequested;
	}

	@Override
	public void run() {
		Log.i(logtag,"Processing loop started. (Thread: " + this.getName() + ")");
		long startTime;		// timestamp when signal processing is started
		long sleepTime;		// time (in ms) to sleep before the next run to meet the frame rate

		while(!stopRequested) {
			// store the current timestamp
			startTime = System.currentTimeMillis();

			//<DEBUG>
//			SamplePacket samples = new SamplePacket(new double[fftSize], new double[fftSize]);
//			for (int i = 0; i < samples.size(); i++) {
//				samples.re()[i] = Math.cos(2 * Math.PI * 0.01 * i);
//				samples.im()[i] = 0; //Math.sin(2 * Math.PI * 0.01 * i);
//			}
			//</DEBUG>

			// fetch the next samples from the queue:
			SamplePacket samples = null;
			try {
				samples = inputQueue.poll(1000 / frameRate, TimeUnit.MILLISECONDS);
				if (samples == null) {
					Log.e(logtag, "run: Timeout while waiting on input data. stop.");
					this.stopLoop();
					break;
				}
			} catch (InterruptedException e) {
				Log.e(logtag, "run: Interrupted while polling from input queue. stop.");
				this.stopLoop();
				break;
			}

			// do the signal processing:
			double[] mag = this.doProcessing(samples);

			// return samples to the buffer pool
			returnQueue.offer(samples);

			// Draw the results on the surface:
			Canvas c = null;
			try {
				c = view.getHolder().lockCanvas();

				synchronized (view.getHolder()) {
					if(c != null)
						view.drawFrame(c, mag, sampleRate, basebandFrequency, frameRate, load);
					else
						Log.d(logtag, "run: Canvas is null.");
				}
			} catch (Exception e)
			{
				Log.e(logtag,"run: Error while drawing on the canvas. Stop!");
				e.printStackTrace();
				break;
			} finally {
				if (c != null) {
					view.getHolder().unlockCanvasAndPost(c);
				}
			}

			// Calculate the remaining time in this frame (according to the frame rate) and sleep
			// for that time:
			sleepTime = (1000/frameRate)-(System.currentTimeMillis() - startTime);
			try {
				if (sleepTime > 0) {
					// load = processing_time / frame_duration
					load = (System.currentTimeMillis() - startTime) / (1000.0 / frameRate);
					Log.d(logtag,"Load: " + load + "; Sleep for " + sleepTime + "ms.");
					sleep(sleepTime);
				}
				else {
					Log.w(logtag, "Couldn't meet requested frame rate!");
					load = 1;
				}
			} catch (Exception e) {
				Log.e(logtag,"Error while calling sleep()");
			}
		}
		this.stopRequested = true;
		Log.i(logtag,"Processing loop stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * This method will do the signal processing (fft) on the given samples
	 *
	 * @param samples	input samples for the signal processing
	 * @return array with the magnitudes of the frequency spectrum (logarithmic)
	 */
	public double[] doProcessing(SamplePacket samples) {
		double[] mag = new double[samples.size()];		// Magnitude of the frequency spectrum

		// Multiply the samples with a Window function:
		this.fftBlock.applyWindow(samples.re(), samples.im());

		// Calculate the fft:
		this.fftBlock.fft(samples.re(), samples.im());

		// Calculate the logarithmic magnitude:
		for (int i = 0; i < samples.size(); i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = (i+samples.size()/2) % samples.size();

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			mag[targetIndex] = Math.log(Math.pow(samples.re(i)/fftSize,2) + Math.pow(samples.im(i)/fftSize,2));
		}
		return mag;
	}
}
