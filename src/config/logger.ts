import pino from "pino";

const SECRET_PATTERN = /\d{6,}:[A-Za-z0-9_-]{20,}/g;
const SECRET_KEYS = new Set(["botToken", "token", "BOT_TOKEN"]);

export function redactSecrets(value: string): string {
  return value.replace(SECRET_PATTERN, "[Redacted]");
}

export function toLogError(error: unknown): {
  type: string;
  message: string;
  stack?: string;
} {
  if (!(error instanceof Error)) {
    return {
      type: "UnknownError",
      message: redactSecrets(String(error)),
    };
  }

  const logged: { type: string; message: string; stack?: string } = {
    type: error.name,
    message: redactSecrets(error.message),
  };
  if (error.stack !== undefined) {
    logged.stack = redactSecrets(error.stack);
  }
  return logged;
}

function redactLogValue(value: unknown): unknown {
  if (typeof value === "string") {
    return redactSecrets(value);
  }
  if (value instanceof Error) {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => redactLogValue(entry));
  }
  if (typeof value === "object" && value !== null) {
    const redacted: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value)) {
      redacted[key] = SECRET_KEYS.has(key) ? "[Redacted]" : redactLogValue(entry);
    }
    return redacted;
  }
  return value;
}

const options = {
  level: "info",
  base: { service: "tradexa" },
  redact: {
    paths: ["botToken", "token", "BOT_TOKEN", "*.botToken", "*.token", "*.BOT_TOKEN"],
    censor: "[Redacted]",
  },
  serializers: {
    err: toLogError,
  },
  hooks: {
    logMethod(args: Parameters<pino.LogFn>, method: pino.LogFn) {
      const redactedArgs = args.map((arg) => redactLogValue(arg));
      method.apply(this, redactedArgs as Parameters<pino.LogFn>);
    },
  },
} satisfies pino.LoggerOptions;

export function createLogger(stream?: pino.DestinationStream): pino.Logger {
  if (stream === undefined) {
    return pino(options);
  }
  return pino(options, stream);
}

export const logger = createLogger();
