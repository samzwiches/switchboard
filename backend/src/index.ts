import { handleRequest } from "./assistant";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    return handleRequest(
      request,
      { apiKey: env.OPENAI_API_KEY ?? "", model: env.OPENAI_MODEL },
      (url, init) => fetch(url, init),
    );
  },
} satisfies ExportedHandler<Env>;
