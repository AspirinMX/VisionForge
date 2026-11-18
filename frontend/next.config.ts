import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The Docker image runs the standalone server; /api/v1/* is proxied at
  // request time by app/api/v1/[...path]/route.ts using
  // VISIONFORGE_API_BASE_URL, so the image can move between environments.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
