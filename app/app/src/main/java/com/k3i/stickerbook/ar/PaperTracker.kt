package com.k3i.stickerbook.ar

import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB

/**
 * Tracks a paper/drawing using ORB keypoints + RANSAC homography.
 *
 * Not thread-safe — call from a single executor (e.g., ImageAnalysis background).
 */
class PaperTracker(
    private val nFeatures: Int = 500,
    private val loweRatio: Double = 0.75,
    private val ransacThreshold: Double = 3.0,
    private val minInliers: Int = 15,
) {
    private val orb: ORB = ORB.create(nFeatures)
    private val matcher: BFMatcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, false)

    private val refKeypoints = MatOfKeyPoint()
    private val refDescriptors = Mat()
    var isReferenceSet: Boolean = false
        private set

    fun setReference(frameGray: Mat) {
        refKeypoints.release()
        refDescriptors.release()
        orb.detectAndCompute(frameGray, Mat(), refKeypoints, refDescriptors)
        isReferenceSet = refKeypoints.toArray().isNotEmpty()
        Log.i(TAG, "reference set: ${refKeypoints.toArray().size} keypoints")
    }

    /**
     * @return 3x3 homography as DoubleArray (row-major), or null if too few matches.
     */
    fun update(frameGray: Mat): DoubleArray? {
        if (!isReferenceSet) return null

        val curKeypoints = MatOfKeyPoint()
        val curDescriptors = Mat()
        orb.detectAndCompute(frameGray, Mat(), curKeypoints, curDescriptors)
        if (curDescriptors.empty()) return null

        val knnMatches = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(refDescriptors, curDescriptors, knnMatches, 2)

        val goodMatches = mutableListOf<DMatch>()
        for (mm in knnMatches) {
            val arr = mm.toArray()
            if (arr.size >= 2 && arr[0].distance < loweRatio * arr[1].distance) {
                goodMatches.add(arr[0])
            }
        }

        if (goodMatches.size < minInliers) {
            Log.w(TAG, "lost: only ${goodMatches.size} good matches")
            return null
        }

        val refPts = refKeypoints.toArray()
        val curPts = curKeypoints.toArray()
        val src = MatOfPoint2f(*goodMatches.map { Point(refPts[it.queryIdx].pt.x, refPts[it.queryIdx].pt.y) }.toTypedArray())
        val dst = MatOfPoint2f(*goodMatches.map { Point(curPts[it.trainIdx].pt.x, curPts[it.trainIdx].pt.y) }.toTypedArray())

        val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, ransacThreshold)
        if (homography.empty()) {
            Log.w(TAG, "findHomography returned empty")
            return null
        }
        val out = DoubleArray(9)
        homography.get(0, 0, out)
        return out
    }

    fun reset() {
        refKeypoints.release()
        refDescriptors.release()
        isReferenceSet = false
    }

    companion object {
        private const val TAG = "PaperTracker"
    }
}
