// DOM-free model of the Argos config: validation and serialization mirror ConfigLoader.kt on the server.
// Loaded by index.html and executed unchanged inside ConfigModelJsTest.
const ArgosConfigModel = (() => {
  const ID_PATTERN = /^[A-Za-z0-9_.-]{1,64}$/;
  const HOST_PATTERN = /^[A-Za-z0-9:][A-Za-z0-9._:%-]{0,253}$/;
  const CHECK_TYPES = ['http', 'tcp', 'ping', 'dns'];
  const TLS_MODES = ['starttls', 'ssl', 'none'];

  const isBlank = (value) => String(value ?? '').trim() === '';
  const isInt = (value) => Number.isInteger(value);
  const list = (values) => '[' + values.join(', ') + ']';

  // Shape checks throw so the upload handler can show a message; the server would reject these files at parse time too.
  const isObject = (value) => value !== null && typeof value === 'object' && Array.isArray(value) === false;
  function asObject(value, path, fallback = {}) {
    if (value == null) return fallback;
    if (isObject(value) === false) throw new TypeError(`${path} must be an object`);
    return value;
  }
  function asList(value, path, fallback = []) {
    if (value == null) return fallback;
    if (Array.isArray(value) === false) throw new TypeError(`${path} must be a list`);
    return value;
  }
  // The server parses leniently: "30" is accepted for a Long. Comparing such strings as numbers here keeps both sides in agreement.
  const num = (value, fallback) => {
    if (value == null) return fallback;
    const numeric = typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value));
    return numeric ? Number(value) : value;
  };
  // A blank optional string means "unset": expectedIp "" would otherwise make a DNS monitor permanently DOWN.
  const optional = (value) => (isBlank(value) ? null : value);

  // Defaults match the Kotlin data classes in AppConfig.kt / CheckConfig.kt. Unknown keys are kept: the server ignores them.
  function normalizeCheck(raw, path) {
    const check = asObject(raw, path);
    const type = check.type ?? 'http';
    if (type === 'http') {
      return {
        ...check,
        type,
        url: check.url ?? '',
        method: check.method ?? 'GET',
        headers: asObject(check.headers, `${path}.headers`),
        expectedStatusCodes: asList(check.expectedStatusCodes, `${path}.expectedStatusCodes`, [200]).map((code) => num(code, code)),
        bodyRegex: optional(check.bodyRegex),
        followRedirects: check.followRedirects ?? true
      };
    }
    if (type === 'tcp') return { ...check, type, host: check.host ?? '', port: num(check.port, null) };
    if (type === 'ping') return { ...check, type, host: check.host ?? '' };
    if (type === 'dns') return { ...check, type, hostname: check.hostname ?? '', expectedIp: optional(check.expectedIp) };
    return { ...check, type };
  }

  function normalizeMonitor(raw, index) {
    const path = `monitors[${index}]`;
    const monitor = asObject(raw, path);
    return {
      ...monitor,
      id: monitor.id ?? '',
      name: monitor.name ?? '',
      intervalSeconds: num(monitor.intervalSeconds, 60),
      timeoutSeconds: num(monitor.timeoutSeconds, 10),
      check: normalizeCheck(monitor.check, `${path}.check`),
      notificationChannelIds: monitor.notificationChannelIds == null ? null : asList(monitor.notificationChannelIds, `${path}.notificationChannelIds`)
    };
  }

  function normalizeSmtp(raw, index) {
    const path = `smtpChannels[${index}]`;
    const smtp = asObject(raw, path);
    return {
      ...smtp,
      id: smtp.id ?? '',
      host: smtp.host ?? '',
      port: num(smtp.port, 587),
      username: smtp.username ?? '',
      password: smtp.password ?? '',
      from: smtp.from ?? '',
      to: asList(smtp.to, `${path}.to`),
      systemEvents: smtp.systemEvents ?? true,
      tls: smtp.tls ?? 'starttls'
    };
  }

  function normalizeWebhook(raw, index) {
    const path = `webhookChannels[${index}]`;
    const webhook = asObject(raw, path);
    return {
      ...webhook,
      id: webhook.id ?? '',
      url: webhook.url ?? '',
      method: webhook.method ?? 'POST',
      headers: asObject(webhook.headers, `${path}.headers`),
      bodyTemplate: webhook.bodyTemplate ?? '',
      systemEvents: webhook.systemEvents ?? true
    };
  }

  function normalizeStatusPage(raw, index) {
    const path = `statusPages[${index}]`;
    const page = asObject(raw, path);
    const auth = asObject(page.basicAuth, `${path}.basicAuth`, null);
    const basicAuth = auth === null ? null : { ...auth, username: auth.username ?? '', passwordHash: auth.passwordHash ?? '' };
    return { ...page, id: page.id ?? '', name: page.name ?? '', monitorIds: asList(page.monitorIds, `${path}.monitorIds`), basicAuth };
  }

  function normalize(raw) {
    if (isObject(raw) === false) throw new TypeError('the top level must be a JSON object');
    const config = raw;
    return {
      ...config,
      monitors: asList(config.monitors, 'monitors').map(normalizeMonitor),
      smtpChannels: asList(config.smtpChannels, 'smtpChannels').map(normalizeSmtp),
      webhookChannels: asList(config.webhookChannels, 'webhookChannels').map(normalizeWebhook),
      statusPages: asList(config.statusPages, 'statusPages').map(normalizeStatusPage),
      retentionDays: num(config.retentionDays, 365),
      flappingThreshold: num(config.flappingThreshold, 3),
      heartbeatGapMinutesThreshold: num(config.heartbeatGapMinutesThreshold, 2),
      dataDir: config.dataDir ?? './data',
      webHost: config.webHost ?? '0.0.0.0',
      webPort: num(config.webPort, 8080)
    };
  }

  function validate(raw) {
    const issues = [];
    const config = normalize(raw);
    const { monitors, smtpChannels, webhookChannels, statusPages } = config;

    function checkId(kind, id) {
      const valid = ID_PATTERN.test(String(id));
      if (valid === false) issues.push(`${kind} ID '${id}' must match [A-Za-z0-9_.-] and be 1-64 characters long`);
    }

    function checkDuplicates(kind, ids) {
      const seen = new Set();
      const duplicates = new Set();
      ids.forEach((id) => (seen.has(id) ? duplicates.add(id) : seen.add(id)));
      if (duplicates.size > 0) issues.push(`Duplicate ${kind} IDs: ${list([...duplicates])}`);
    }

    function checkHost(owner, host) {
      const valid = HOST_PATTERN.test(String(host));
      if (valid === false) issues.push(`${owner}: host '${host}' must be a hostname or IP address (letters, digits, '.', '-', ':', '%', not starting with '-')`);
    }

    // One message per field: "positive" covers missing/NaN values, "whole number" the fractional ones the server's Long/Int parser refuses.
    function checkPositiveInt(label, value) {
      if ((value > 0) === false) issues.push(`${label} must be positive`);
      else if (isInt(value) === false) issues.push(`${label} must be a whole number`);
    }

    function checkBoolean(label, value) {
      if (typeof value !== 'boolean') issues.push(`${label} must be true or false`);
    }

    function checkPort(owner, port) {
      if (isInt(port) === false) {
        issues.push(`${owner}: port must be a whole number`);
        return;
      }
      const valid = port >= 1 && port <= 65535;
      if (valid === false) issues.push(`${owner}: port ${port} is out of range 1-65535`);
    }

    checkDuplicates('monitor', monitors.map((m) => m.id));
    checkDuplicates('channel', [...smtpChannels, ...webhookChannels].map((c) => c.id));
    checkDuplicates('status page', statusPages.map((p) => p.id));

    const monitorIds = new Set(monitors.map((m) => m.id));
    const channelIds = new Set([...smtpChannels, ...webhookChannels].map((c) => c.id));

    monitors.forEach((monitor) => {
      const owner = `Monitor '${monitor.id}'`;
      checkId('Monitor', monitor.id);
      checkPositiveInt(`${owner}: intervalSeconds`, monitor.intervalSeconds);
      checkPositiveInt(`${owner}: timeoutSeconds`, monitor.timeoutSeconds);
      const tooSlow = monitor.timeoutSeconds >= monitor.intervalSeconds;
      if (tooSlow) issues.push(`${owner}: timeoutSeconds must be smaller than intervalSeconds`);

      const unknownChannels = (monitor.notificationChannelIds ?? []).filter((id) => channelIds.has(id) === false);
      if (unknownChannels.length > 0) issues.push(`${owner}: unknown notificationChannelIds ${list(unknownChannels)}`);

      const check = monitor.check;
      if (check.type === 'http') {
        if (isBlank(check.url)) issues.push(`${owner}: url must not be blank`);
        if (check.expectedStatusCodes.length === 0) issues.push(`${owner}: expectedStatusCodes must not be empty`);
        const regexError = check.bodyRegex == null ? null : regexProblem(check.bodyRegex);
        if (regexError != null) issues.push(`${owner}: bodyRegex is invalid (${regexError})`);
        checkBoolean(`${owner}: followRedirects`, check.followRedirects);
      } else if (check.type === 'tcp') {
        checkHost(owner, check.host);
        checkPort(owner, check.port);
      } else if (check.type === 'ping') {
        checkHost(owner, check.host);
      } else if (check.type === 'dns') {
        checkHost(owner, check.hostname);
      } else {
        issues.push(`${owner}: check type must be one of ${CHECK_TYPES.join(', ')}`);
      }
    });

    smtpChannels.forEach((smtp) => {
      const owner = `SMTP channel '${smtp.id}'`;
      checkId('Channel', smtp.id);
      if (isBlank(smtp.host)) issues.push(`${owner}: host must not be blank`);
      checkPort(owner, smtp.port);
      if (smtp.to.length === 0) issues.push(`${owner}: 'to' must contain at least one recipient`);
      if (isBlank(smtp.from)) issues.push(`${owner}: 'from' must not be blank`);
      if (TLS_MODES.includes(smtp.tls) === false) issues.push(`${owner}: tls must be one of ${TLS_MODES.join(', ')}`);
      checkBoolean(`${owner}: systemEvents`, smtp.systemEvents);
    });

    webhookChannels.forEach((webhook) => {
      checkId('Channel', webhook.id);
      const owner = `Webhook channel '${webhook.id}'`;
      if (isBlank(webhook.url)) issues.push(`${owner}: url must not be blank`);
      checkBoolean(`${owner}: systemEvents`, webhook.systemEvents);
    });

    statusPages.forEach((page) => {
      checkId('Status page', page.id);
      const unknownMonitors = page.monitorIds.filter((id) => monitorIds.has(id) === false);
      if (unknownMonitors.length > 0) issues.push(`Status page '${page.id}': unknown monitorIds ${list(unknownMonitors)}`);
    });

    checkPositiveInt('retentionDays', config.retentionDays);
    checkPositiveInt('flappingThreshold', config.flappingThreshold);
    checkPositiveInt('heartbeatGapMinutesThreshold', config.heartbeatGapMinutesThreshold);
    checkPort('webPort', config.webPort);
    if (isBlank(config.webHost)) issues.push('webHost must not be blank');

    return issues;
  }

  // The browser can only check JavaScript regex syntax; the server compiles java.util.regex, which is a superset for common patterns.
  function regexProblem(pattern) {
    try {
      new RegExp(pattern);
      return null;
    } catch (error) {
      return error.message;
    }
  }

  // Every nullable field (notificationChannelIds, bodyRegex, expectedIp, basicAuth) means the same as an absent one, so the file stays as short as the README example.
  const withoutNulls = (object) => Object.fromEntries(Object.entries(object).filter(([, value]) => value !== null));

  function serialize(config) {
    const output = {
      ...config,
      monitors: config.monitors.map((monitor) => withoutNulls({ ...monitor, check: withoutNulls(monitor.check) })),
      statusPages: config.statusPages.map(withoutNulls)
    };
    return JSON.stringify(output, null, 2) + '\n';
  }

  const TRANSLITERATIONS = { ä: 'ae', ö: 'oe', ü: 'ue', ß: 'ss' };

  function suggestId(name) {
    return String(name ?? '')
      .toLowerCase()
      .replace(/[äöüß]/g, (char) => TRANSLITERATIONS[char])
      .replace(/[^a-z0-9_.-]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 64);
  }

  return {
    normalize,
    validate,
    serialize,
    suggestId,
    newMonitor: () => normalizeMonitor({}, 0),
    newCheck: (type) => normalizeCheck({ type }, 'check'),
    newSmtpChannel: () => normalizeSmtp({}, 0),
    newWebhookChannel: () => normalizeWebhook({}, 0),
    newStatusPage: () => normalizeStatusPage({}, 0)
  };
})();
