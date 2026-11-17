package app.pagefault.visionforge.agent.provider.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

import app.pagefault.visionforge.agent.generation.GenerationType;
import app.pagefault.visionforge.agent.model.MediaProtocol;
import app.pagefault.visionforge.common.error.SystemException;
import app.pagefault.visionforge.common.error.CommonErrorCode;

/**
 * Deterministic offline provider used to exercise the full task lifecycle without a gateway.
 *
 * <p>Images are real PNGs. Video outputs are placeholder MP4-labelled bytes: this adapter proves
 * acceptance, archiving, delivery and billing, not decodable video. Real protocol adapters replace
 * it once the gateway contract has been verified.
 */
public class StubMediaProvider implements MediaProvider {

    private static final int IMAGE_SIZE = 768;

    @Override
    public MediaProtocol protocol() {
        return MediaProtocol.STUB;
    }

    @Override
    public MediaProviderOutcome start(MediaProviderRequest request) {
        MediaProviderReceipt receipt = new MediaProviderReceipt(
                "stub-" + request.taskId(),
                null,
                "succeeded",
                "{\"stub\":true}");
        if (request.type() == GenerationType.IMAGE) {
            return new MediaProviderOutcome.Completed(
                    receipt,
                    List.of(new MediaArtifact(0, "image/png", renderPng(request.prompt()))));
        }
        String marker = "VisionForge offline video stub\n" + request.prompt() + "\n";
        return new MediaProviderOutcome.Completed(
                receipt,
                List.of(new MediaArtifact(
                        0, "video/mp4", marker.getBytes(StandardCharsets.UTF_8))));
    }

    private byte[] renderPng(String prompt) {
        try {
            BufferedImage image = new BufferedImage(
                    IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                int seed = prompt == null ? 0 : prompt.hashCode();
                Color base = new Color(
                        40 + Math.floorMod(seed, 180),
                        40 + Math.floorMod(seed / 7, 180),
                        40 + Math.floorMod(seed / 31, 180));
                graphics.setColor(base);
                graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
                graphics.setColor(base.darker());
                for (int x = -IMAGE_SIZE; x < IMAGE_SIZE; x += 64) {
                    graphics.drawLine(x, 0, x + IMAGE_SIZE, IMAGE_SIZE);
                }
                graphics.setColor(base.brighter());
                for (int y = 0; y < IMAGE_SIZE; y += 96) {
                    graphics.fillRect(0, y, IMAGE_SIZE, 8);
                }
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new SystemException(
                        CommonErrorCode.INTERNAL_ERROR, "PNG encoder is unavailable", null);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new SystemException(
                    CommonErrorCode.INTERNAL_ERROR, "Could not render stub image", exception);
        }
    }
}
