package processing;

final class Yin implements ISoundDetection {

    private static final double DEFAULT_THRESHOLD = 0.32;

	/**
	 * The default size of an audio buffer (in samples).
	 */
	public static final int DEFAULT_BUFFER_SIZE = 2048;

	/**
	 * The default overlap of two consecutive audio buffers (in samples).
	 */
	public static final int DEFAULT_OVERLAP = 1536;

	/**
	 * The actual YIN threshold.
	 */
	private final double threshold;

	/**
	 * The audio sample rate. Most audio has a sample rate of 44.1kHz.
	 */
	private final float sampleRate;

	/**
	 * The buffer that stores the calculated values. It is exactly half the size
	 * of the input buffer.
	 */
	private final float[] yinBuffer;
	
	/**
	 * The result of the processing detection iteration.
	 */
	private final SoundDetectionResult result;

	/**
	 * Create a new processing detector for a stream with the defined sample rate.
	 * Processes the audio in blocks of the defined size.
	 * 
	 * @param audioSampleRate
	 *            The sample rate of the audio stream. E.g. 44.1 kHz.
	 * @param bufferSize
	 *            The size of a buffer. E.g. 1024.
	 */
	public Yin(final float audioSampleRate, final int bufferSize) {
		this(audioSampleRate, bufferSize, DEFAULT_THRESHOLD);
	}

	/**
	 * Create a new processing detector for a stream with the defined sample rate.
	 * Processes the audio in blocks of the defined size.
	 * 
	 * @param audioSampleRate
	 *            The sample rate of the audio stream. E.g. 44.1 kHz.
	 * @param bufferSize
	 *            The size of a buffer. E.g. 1024.
	 * @param yinThreshold
	 *            The parameter that defines which peaks are kept as possible
	 *            processing candidates. See the YIN paper for more details.
	 */
	public Yin(final float audioSampleRate, final int bufferSize, final double yinThreshold) {
		this.sampleRate = audioSampleRate;
		this.threshold = yinThreshold;
		yinBuffer = new float[bufferSize / 2];
		result = new SoundDetectionResult();
	}

	/**
	 * The app flow of the YIN algorithm. Returns a processing value in Hz or -1 if
	 * no processing is detected.
	 * 
	 * @return a processing value in Hz or -1 if no processing is detected.
	 */
	public SoundDetectionResult getSound(final float[] audioBuffer) {

		final int tauEstimate;
		final float pitchInHertz;

		// step 2
		difference(audioBuffer);

		// step 3
		cumulativeMeanNormalizedDifference();

		// step 4
		tauEstimate = absoluteThreshold();

		// step 5
		if (tauEstimate != -1) {
			final float betterTau = parabolicInterpolation(tauEstimate);

			// step 6
			// TODO Implement optimization for the AUBIO_YIN algorithm.
			// 0.77% => 0.5% error rate,
			// using the data of the YIN paper
			// bestLocalEstimate()

			// conversion to Hz
			pitchInHertz = sampleRate / betterTau;
		} else{
			// no processing found
			pitchInHertz = -1;
		}
		
		result.setFrequency(pitchInHertz);

		return result;
	}

	/**
	 * Implements the difference function as described in step 2 of the YIN
	 * paper.
	 */
	private void difference(final float[] audioBuffer) {
		int index, tau;
		float delta;
		for (tau = 0; tau < yinBuffer.length; tau++) {
			yinBuffer[tau] = 0;
		}
		for (tau = 1; tau < yinBuffer.length; tau++) {
			for (index = 0; index < yinBuffer.length; index++) {
				delta = audioBuffer[index] - audioBuffer[index + tau];
				yinBuffer[tau] += delta * delta;
			}
		}
	}

	private void cumulativeMeanNormalizedDifference() {
		int tau;
		yinBuffer[0] = 1;
		float runningSum = 0;
		for (tau = 1; tau < yinBuffer.length; tau++) {
			runningSum += yinBuffer[tau];
			yinBuffer[tau] *= tau / runningSum;
		}
	}

	private int absoluteThreshold() {
		// Uses another loop construct
		// than the AUBIO implementation
		int tau;
		// first two positions in yinBuffer are always 1
		// So start at the third (index 2)
		for (tau = 2; tau < yinBuffer.length; tau++) {
			if (yinBuffer[tau] < threshold) {
				while (tau + 1 < yinBuffer.length && yinBuffer[tau + 1] < yinBuffer[tau]) {
					tau++;
				}
				// found tau, exit loop and return
				// store the probability
				// From the YIN paper: The threshold determines the list of
				// candidates admitted to the set, and can be interpreted as the
				// proportion of aperiodic power tolerated
				// within a periodic signal.
				//
				// Since we want the periodicity and and not aperiodicity:
				// periodicity = 1 - aperiodicity
				result.setProbability(1 - yinBuffer[tau]);
				break;
			}
		}

		
		// if no processing found, tau => -1
		if (tau == yinBuffer.length || yinBuffer[tau] >= threshold) {
			tau = -1;
			result.setProbability(0);
			result.setPitched(false);	
		} else {
			result.setPitched(true);
		}

		return tau;
	}

	/**
	 * Implements step 5 of the AUBIO_YIN paper. It refines the estimated tau
	 * value using parabolic interpolation. This is needed to detect higher
	 * frequencies more precisely. See http://fizyka.umk.pl/nrbook/c10-2.pdf and
	 * for more background
	 * http://fedc.wiwi.hu-berlin.de/xplore/tutorials/xegbohtmlnode62.html
	 * 
	 * @param tauEstimate
	 *            The estimated tau value.
	 * @return A better, more precise tau value.
	 */
	private float parabolicInterpolation(final int tauEstimate) {
		final float betterTau;
		final int x0;
		final int x2;

		if (tauEstimate < 1) {
			x0 = tauEstimate;
		} else {
			x0 = tauEstimate - 1;
		}
		if (tauEstimate + 1 < yinBuffer.length) {
			x2 = tauEstimate + 1;
		} else {
			x2 = tauEstimate;
		}
		if (x0 == tauEstimate) {
			if (yinBuffer[tauEstimate] <= yinBuffer[x2]) {
				betterTau = tauEstimate;
			} else {
				betterTau = x2;
			}
		} else if (x2 == tauEstimate) {
			if (yinBuffer[tauEstimate] <= yinBuffer[x0]) {
				betterTau = tauEstimate;
			} else {
				betterTau = x0;
			}
		} else {
			float s0, s1, s2;
			s0 = yinBuffer[x0];
			s1 = yinBuffer[tauEstimate];
			s2 = yinBuffer[x2];
			// fixed AUBIO implementation, thanks to Karl Helgason:
			// (2.0f * s1 - s2 - s0) was incorrectly multiplied with -1
			betterTau = tauEstimate + (s2 - s0) / (2 * (2 * s1 - s2 - s0));
		}
		return betterTau;
	}
}
