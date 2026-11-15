@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.ilinich.stackedalphavideo

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.addOutput
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setMuted
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVMetalTextureCacheCreate
import platform.CoreVideo.CVMetalTextureCacheCreateTextureFromImage
import platform.CoreVideo.CVMetalTextureCacheRef
import platform.CoreVideo.CVMetalTextureCacheRefVar
import platform.CoreVideo.CVMetalTextureGetTexture
import platform.CoreVideo.CVMetalTextureRef
import platform.CoreVideo.CVMetalTextureRefVar
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetIOSurface
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferMetalCompatibilityKey
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Metal.MTLClearColorMake
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPrimitiveTypeTriangleStrip
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLRenderPipelineDescriptor
import platform.Metal.MTLRenderPipelineStateProtocol
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageShaderRead
import platform.MetalKit.MTKView
import platform.MetalKit.MTKViewDelegateProtocol
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIColor
import platform.darwin.NSObject
import objcnames.protocols.MTLDeviceProtocol as ForwardMTLDevice

/**
 * Metal version of the Android shader. The vertex stage emits one quad with top-left texture
 * origin, so the top (RGB) half is v in [0, 0.5] and the bottom (matte) half is v in [0.5, 1].
 * The fragment stage returns premultiplied colour, which a non-opaque CAMetalLayer composites
 * over whatever is behind it.
 */
private const val SHADER = """
#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 rgbTexCoord;
    float2 alphaTexCoord;
};

vertex VertexOut stackedAlphaVertex(uint vid [[vertex_id]]) {
    float2 positions[4] = { float2(-1, -1), float2(1, -1), float2(-1, 1), float2(1, 1) };
    float2 position = positions[vid];
    float2 uv = float2((position.x + 1.0) * 0.5, (1.0 - position.y) * 0.5);
    VertexOut out;
    out.position = float4(position, 0.0, 1.0);
    out.rgbTexCoord = float2(uv.x, uv.y * 0.5);
    out.alphaTexCoord = float2(uv.x, uv.y * 0.5 + 0.5);
    return out;
}

fragment float4 stackedAlphaFragment(VertexOut in [[stage_in]],
                                     texture2d<float> frame [[texture(0)]]) {
    constexpr sampler linearSampler(address::clamp_to_edge, filter::linear);
    float3 rgb = frame.sample(linearSampler, in.rgbTexCoord).rgb;
    float alpha = frame.sample(linearSampler, in.alphaTexCoord).g;
    return float4(rgb * alpha, alpha);
}
"""

/**
 * How many imported frames stay retained. A frame can still be read by the GPU after a newer one
 * arrives (MTKView keeps up to three drawables in flight), so it is released three frames later.
 */
private const val FRAMES_IN_FLIGHT = 3

/**
 * Plays [uri] with AVPlayer and draws each decoded frame onto an [MTKView] with the stacked-alpha
 * shader.
 *
 * Frames come from an [AVPlayerItemVideoOutput] as BGRA pixel buffers. On a device they are
 * IOSurface-backed and imported zero-copy through a CVMetalTextureCache; the simulator hands out
 * buffers without an IOSurface, which are uploaded with `replaceRegion` instead. The view only
 * presents when a new frame arrives, so a paused video costs nothing per tick.
 *
 * All methods run on the main thread: MTKView calls its delegate there by default.
 */
internal class StackedAlphaMetalRenderer(
    uri: String,
    private val state: StackedAlphaVideoState,
) : NSObject(), MTKViewDelegateProtocol {

    private val device: MTLDeviceProtocol = requireNotNull(MTLCreateSystemDefaultDevice()) { "Metal is not available" }
    private val queue: MTLCommandQueueProtocol = requireNotNull(device.newCommandQueue())
    private val pipeline: MTLRenderPipelineStateProtocol = buildPipeline(device)
    private val textureCache: CVMetalTextureCacheRef? = memScoped {
        val cache = alloc<CVMetalTextureCacheRefVar>()
        CVMetalTextureCacheCreate(null, null, device as ForwardMTLDevice, null, cache.ptr)
        cache.value
    }

    private val output = AVPlayerItemVideoOutput(
        pixelBufferAttributes = mapOf(
            CFBridgingRelease(kCVPixelBufferPixelFormatTypeKey) to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA),
            CFBridgingRelease(kCVPixelBufferMetalCompatibilityKey) to NSNumber(bool = true),
        ),
    )
    private val item = AVPlayerItem(uRL = requireNotNull(NSURL.URLWithString(uri)) { "Invalid video uri: $uri" })
    private val player = AVPlayer(playerItem = item)

    /** AVPlayer only keeps decoding while it is attached to a layer, so a hidden 1×1 one is kept. */
    private val playerLayer = AVPlayerLayer().apply {
        setPlayer(player)
        setFrame(CGRectMake(0.0, 0.0, 1.0, 1.0))
        setHidden(true)
    }

    val view: MTKView = MTKView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0), device = device).apply {
        delegate = this@StackedAlphaMetalRenderer
        framebufferOnly = false
        setOpaque(false)
        layer.setOpaque(false)
        backgroundColor = UIColor.clearColor
        clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0)
        enableSetNeedsDisplay = false
        paused = false
        layer.addSublayer(playerLayer)
    }

    private var currentTexture: MTLTextureProtocol? = null
    private val retainedFrames = ArrayDeque<CVMetalTextureRef>()
    private var uploadTexture: MTLTextureProtocol? = null
    private var needsRedraw = false
    private var looping = true
    private var reachedEnd = false
    private var released = false

    private val endObserver: Any = NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVPlayerItemDidPlayToEndTimeNotification,
        `object` = item,
        queue = NSOperationQueue.mainQueue,
    ) { _: NSNotification? -> onPlayedToEnd() }

    init {
        item.addOutput(output)
    }

    fun applyPlayback(playing: Boolean, looping: Boolean, muted: Boolean) {
        if (released) return
        this.looping = looping
        if (player.muted != muted) player.setMuted(muted)
        if (playing) {
            if (reachedEnd) {
                reachedEnd = false
                player.seekToTime(CMTimeMake(value = 0, timescale = 1))
            }
            player.play()
        } else {
            player.pause()
        }
    }

    fun release() {
        if (released) return
        released = true
        view.paused = true
        view.delegate = null
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        playerLayer.removeFromSuperlayer()
        NSNotificationCenter.defaultCenter.removeObserver(endObserver)
        currentTexture = null
        uploadTexture = null
        while (retainedFrames.isNotEmpty()) CFRelease(retainedFrames.removeFirst())
        textureCache?.let { CFRelease(it) }
    }

    override fun drawInMTKView(view: MTKView) {
        if (pullNewFrame()) needsRedraw = true
        if (!needsRedraw) return
        val texture = currentTexture ?: return
        val drawable = view.currentDrawable ?: return
        val pass = view.currentRenderPassDescriptor ?: return
        val commandBuffer = queue.commandBuffer() ?: return
        val encoder = commandBuffer.renderCommandEncoderWithDescriptor(pass) ?: return

        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(texture, atIndex = 0u)
        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, vertexStart = 0u, vertexCount = 4u)
        encoder.endEncoding()
        commandBuffer.presentDrawable(drawable)
        commandBuffer.commit()
        needsRedraw = false

        if (!state.isFirstFrameRendered) state.isFirstFrameRendered = true
    }

    override fun mtkView(view: MTKView, drawableSizeWillChange: CValue<CGSize>) {
        needsRedraw = true
    }

    private fun onPlayedToEnd() {
        if (released) return
        if (looping) {
            player.seekToTime(CMTimeMake(value = 0, timescale = 1))
            player.play()
        } else {
            reachedEnd = true
            state.isPlaying = false
        }
    }

    /** Imports the frame due at this vsync, if the decoder has a new one. */
    private fun pullNewFrame(): Boolean {
        val itemTime: CValue<CMTime> = output.itemTimeForHostTime(CACurrentMediaTime())
        if (!output.hasNewPixelBufferForItemTime(itemTime)) return false
        // The "copy" in the name means the buffer comes retained and has to be released here.
        val pixelBuffer = output.copyPixelBufferForItemTime(itemTime, itemTimeForDisplay = null) ?: return false
        try {
            state.onSourceSize(
                width = CVPixelBufferGetWidth(pixelBuffer).toInt(),
                height = CVPixelBufferGetHeight(pixelBuffer).toInt(),
            )
            val texture = if (CVPixelBufferGetIOSurface(pixelBuffer) != null) {
                importZeroCopy(pixelBuffer)
            } else {
                uploadCopy(pixelBuffer)
            }
            if (texture != null) currentTexture = texture
            return texture != null
        } finally {
            CVPixelBufferRelease(pixelBuffer)
        }
    }

    private fun importZeroCopy(pixelBuffer: CVPixelBufferRef): MTLTextureProtocol? {
        val cache = textureCache ?: return null
        val frame = memScoped {
            val textureRef = alloc<CVMetalTextureRefVar>()
            CVMetalTextureCacheCreateTextureFromImage(
                null,
                cache,
                pixelBuffer,
                null,
                MTLPixelFormatBGRA8Unorm,
                CVPixelBufferGetWidth(pixelBuffer),
                CVPixelBufferGetHeight(pixelBuffer),
                0u,
                textureRef.ptr,
            )
            textureRef.value
        } ?: return null
        // The Metal texture is only valid while its CVMetalTexture is alive.
        retainedFrames.addLast(frame)
        while (retainedFrames.size > FRAMES_IN_FLIGHT) CFRelease(retainedFrames.removeFirst())
        @Suppress("USELESS_CAST")
        return CVMetalTextureGetTexture(frame) as MTLTextureProtocol?
    }

    private fun uploadCopy(pixelBuffer: CVPixelBufferRef): MTLTextureProtocol? {
        CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        try {
            val base = CVPixelBufferGetBaseAddress(pixelBuffer) ?: return null
            val width = CVPixelBufferGetWidth(pixelBuffer)
            val height = CVPixelBufferGetHeight(pixelBuffer)
            val texture = uploadTextureOfSize(width = width, height = height)
            texture.replaceRegion(
                region = MTLRegionMake2D(0u, 0u, width, height),
                mipmapLevel = 0u,
                withBytes = base,
                bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer),
            )
            return texture
        } finally {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
        }
    }

    private fun uploadTextureOfSize(width: ULong, height: ULong): MTLTextureProtocol {
        uploadTexture?.let { existing ->
            if (existing.width == width && existing.height == height) return existing
        }
        val descriptor = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
            pixelFormat = MTLPixelFormatBGRA8Unorm,
            width = width,
            height = height,
            mipmapped = false,
        )
        descriptor.usage = MTLTextureUsageShaderRead
        return requireNotNull(device.newTextureWithDescriptor(descriptor)).also { uploadTexture = it }
    }
}

private fun buildPipeline(device: MTLDeviceProtocol): MTLRenderPipelineStateProtocol = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val library = device.newLibraryWithSource(SHADER, options = null, error = error.ptr)
        ?: error("Metal shader compilation failed: ${error.value?.localizedDescription}")
    val descriptor = MTLRenderPipelineDescriptor().apply {
        vertexFunction = library.newFunctionWithName("stackedAlphaVertex")
        fragmentFunction = library.newFunctionWithName("stackedAlphaFragment")
        colorAttachments.objectAtIndexedSubscript(0u).setPixelFormat(MTLPixelFormatBGRA8Unorm)
    }
    device.newRenderPipelineStateWithDescriptor(descriptor, error = error.ptr)
        ?: error("Metal pipeline creation failed: ${error.value?.localizedDescription}")
}
