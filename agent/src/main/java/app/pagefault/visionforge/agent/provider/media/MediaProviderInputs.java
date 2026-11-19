package app.pagefault.visionforge.agent.provider.media;

import java.util.Base64;

/**
 * Resolves a media input for an upstream provider. Archived bytes are preferred so the provider
 * never has to reach a private object store; a presigned URL is only a fallback for inputs that
 * were too large to read.
 */
final class MediaProviderInputs {

    private MediaProviderInputs() {
    }

    static String inlineOrUrl(MediaProviderInput input) {
        if (input.content() != null && input.content().length > 0) {
            String mimeType = input.mimeType() == null || input.mimeType().isBlank()
                    ? "image/png"
                    : input.mimeType();
            return "data:" + mimeType + ";base64,"
                    + Base64.getEncoder().encodeToString(input.content());
        }
        return input.url();
    }
}
