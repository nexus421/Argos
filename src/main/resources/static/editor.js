// DOM layer of the setup page: one normalized config object is the single source of truth, every input writes into it.
// Nothing here talks to the server — files are read with FileReader and produced as Blob downloads only.
(() => {
  const model = ArgosConfigModel;
  let config = model.normalize({});
  // Monitors and status pages whose ID still follows their name; typing into the ID field removes them.
  const autoIds = new WeakSet();
  // The notification radio a monitor shows; kept apart from the data because an empty selection and "none" both serialize as [].
  let notifyModes = new WeakMap();
  // Re-rendered on every change because they list IDs that may just have been edited elsewhere.
  const referencePickers = [];
  let uid = 0;

  const $ = (selector, root = document) => root.querySelector(selector);

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    Object.entries(attrs).forEach(([key, value]) => {
      if (key === 'class') node.className = value;
      else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else if (key === 'checked' || key === 'disabled' || key === 'hidden' || key === 'value') node[key] = value;
      else node.setAttribute(key, value);
    });
    children.flat().forEach((child) => { if (child != null) node.append(child); });
    return node;
  }

  // ---- generic fields --------------------------------------------------------------------------

  function field(label, control, hint, className = '') {
    return el('label', { class: `field ${className}`.trim() }, el('span', {}, label), control, hint ? el('small', {}, hint) : null);
  }

  function textField(object, key, label, hint, options = {}) {
    const input = el('input', {
      type: options.type ?? 'text',
      value: object[key] ?? '',
      placeholder: options.placeholder ?? '',
      oninput: (event) => {
        const value = event.target.value;
        object[key] = options.nullable && value === '' ? null : value;
        refresh();
      }
    });
    return field(label, input, hint, options.className);
  }

  function numberField(object, key, label, hint) {
    const input = el('input', {
      type: 'number',
      value: Number.isFinite(object[key]) ? object[key] : '',
      // Number() keeps "1e3" as 1000 (parseInt would stop at "1"); an empty field stays unset, which validate() reports
      oninput: (event) => { object[key] = event.target.value.trim() === '' ? NaN : Number(event.target.value); refresh(); }
    });
    return field(label, input, hint);
  }

  function checkboxField(object, key, label, hint) {
    const input = el('input', { type: 'checkbox', checked: object[key] === true, onchange: (event) => { object[key] = event.target.checked; refresh(); } });
    return el('label', { class: 'field inline' }, input, el('span', {}, label), hint ? el('small', {}, hint) : null);
  }

  function selectField(object, key, label, hint, options, onChange = refresh) {
    const select = el('select', { onchange: (event) => { object[key] = event.target.value; onChange(); } },
      options.map((option) => el('option', { value: option.value }, option.label)));
    select.value = object[key];
    return field(label, select, hint);
  }

  function passwordField(object, key, label, hint) {
    const input = el('input', { type: 'password', value: object[key], autocomplete: 'off', oninput: (event) => { object[key] = event.target.value; refresh(); } });
    const toggle = el('button', { type: 'button', class: 'small', onclick: () => {
      input.type = input.type === 'password' ? 'text' : 'password';
      toggle.textContent = input.type === 'password' ? 'Show' : 'Hide';
    } }, 'Show');
    return field(label, el('div', { class: 'with-button' }, input, toggle), hint);
  }

  function idField(object, hint) {
    return el('input', { type: 'text', value: object.id, oninput: (event) => { object.id = event.target.value; autoIds.delete(object); refresh(); } });
  }

  function nameAndIdFields(object, idHint) {
    const idInput = idField(object);
    const nameInput = el('input', { type: 'text', value: object.name, oninput: (event) => {
      object.name = event.target.value;
      if (autoIds.has(object)) {
        object.id = model.suggestId(object.name);
        idInput.value = object.id;
      }
      refresh();
    } });
    return [field('Name', nameInput, 'Shown on status pages and in alerts.'), field('ID', idInput, idHint)];
  }

  function listField(object, key, label, hint) {
    const textarea = el('textarea', { value: object[key].join('\n'), oninput: (event) => {
      object[key] = event.target.value.split(/[\n,;]/).map((entry) => entry.trim()).filter((entry) => entry !== '');
      refresh();
    } });
    return field(label, textarea, hint, 'wide');
  }

  function headersEditor(object, hint) {
    const pairs = Object.entries(object.headers);
    const rows = el('div');
    const duplicateNote = el('small', { class: 'error' });
    function write() {
      const names = pairs.map(([name]) => name.trim()).filter((name) => name !== '');
      const duplicates = [...new Set(names.filter((name, index) => names.indexOf(name) !== index))];
      duplicateNote.textContent = duplicates.length === 0 ? '' : `Duplicate header names, only the last value is kept: ${duplicates.join(', ')}`;
      object.headers = Object.fromEntries(pairs.filter(([name]) => name.trim() !== ''));
      refresh();
    }
    function renderRows() {
      rows.replaceChildren(
        ...pairs.map((pair, index) => el('div', { class: 'kv' },
          el('input', { type: 'text', value: pair[0], placeholder: 'Header name', oninput: (event) => { pair[0] = event.target.value; write(); } }),
          el('input', { type: 'text', value: pair[1], placeholder: 'Value', oninput: (event) => { pair[1] = event.target.value; write(); } }),
          el('button', { type: 'button', class: 'small danger', onclick: () => { pairs.splice(index, 1); write(); renderRows(); } }, 'Remove')
        )),
        el('button', { type: 'button', class: 'small', onclick: () => { pairs.push(['', '']); renderRows(); rows.querySelector('.kv:last-of-type input').focus(); } }, '+ Add header')
      );
    }
    renderRows();
    return el('div', { class: 'field wide' }, el('span', {}, 'Headers'), rows, duplicateNote, hint ? el('small', {}, hint) : null);
  }

  /** Checkbox list of IDs that is rebuilt on every change; IDs that no longer exist stay visible so they can be unticked. */
  function referencePicker(container, availableIds, selectedIds, texts, onToggle) {
    function render() {
      const available = availableIds().filter((id) => id !== '');
      const selected = selectedIds();
      const ids = [...new Set([...available, ...selected])];
      const note = ids.length === 0 ? texts.none : selected.length === 0 ? texts.nothingTicked : null;
      const children = ids.map((id) => el('label', {},
        el('input', { type: 'checkbox', checked: selected.includes(id), onchange: (event) => { onToggle(id, event.target.checked); refresh(); } }),
        available.includes(id) ? id : `${id} (unknown)`
      ));
      if (note) children.push(el('span', { class: 'muted' }, note));
      container.replaceChildren(...children);
    }
    referencePickers.push(render);
    render();
    return container;
  }

  function toggleIn(list, id, on) {
    const set = new Set(list);
    on ? set.add(id) : set.delete(id);
    return [...set];
  }

  function removeButton(list, index, label) {
    return el('button', { type: 'button', class: 'small danger', onclick: () => { list.splice(index, 1); renderAll(); } }, label);
  }

  function card(title, list, index, removeLabel, ...content) {
    return el('div', { class: 'card' }, el('div', { class: 'card-head' }, el('h3', {}, title), removeButton(list, index, removeLabel)), ...content);
  }

  // ---- monitors --------------------------------------------------------------------------------

  const CHECK_TYPES = [
    { value: 'http', label: 'HTTP(S) request' },
    { value: 'tcp', label: 'TCP port' },
    { value: 'ping', label: 'Ping (ICMP)' },
    { value: 'dns', label: 'DNS resolution' }
  ];

  function checkFields(check) {
    if (check.type === 'http') return [
      textField(check, 'url', 'URL', 'Including the scheme, e.g. https://api.example.com/health', { type: 'url', className: 'wide' }),
      textField(check, 'method', 'Method', 'GET, HEAD, POST, …'),
      statusCodesField(check),
      textField(check, 'bodyRegex', 'Body regex (optional)', 'Java regex matched against the first 1 MiB of the response. Empty: the body is not read.', { nullable: true, className: 'wide' }),
      checkboxField(check, 'followRedirects', 'Follow redirects'),
      headersEditor(check, 'Sent with every request, e.g. Authorization: Bearer <token>.')
    ];
    if (check.type === 'tcp') return [
      textField(check, 'host', 'Host', 'Hostname or IP address, no scheme.'),
      numberField(check, 'port', 'Port', '1–65535')
    ];
    if (check.type === 'ping') return [textField(check, 'host', 'Host', 'Hostname or IP address.')];
    if (check.type === 'dns') return [
      textField(check, 'hostname', 'Hostname', 'The name to resolve.'),
      textField(check, 'expectedIp', 'Expected IP (optional)', 'Must be one of the resolved addresses. Empty: any answer counts as UP.', { nullable: true })
    ];
    return [el('p', { class: 'error' }, `Unknown check type "${check.type}" – pick one above.`)];
  }

  function statusCodesField(check) {
    const input = el('input', { type: 'text', value: check.expectedStatusCodes.join(', '), oninput: (event) => {
      check.expectedStatusCodes = event.target.value.split(/[\s,;]+/).filter((code) => code !== '').map(Number).filter(Number.isInteger);
      refresh();
    } });
    return field('Expected status codes', input, 'Comma-separated, e.g. 200, 204.');
  }

  function notificationFields(monitor) {
    const initialMode = monitor.notificationChannelIds === null ? 'all' : monitor.notificationChannelIds.length === 0 ? 'none' : 'selected';
    if (notifyModes.has(monitor) === false) notifyModes.set(monitor, initialMode);
    const name = `notify-${uid++}`;
    const picker = el('div', { class: 'choices' });
    picker.hidden = notifyModes.get(monitor) !== 'selected';

    const radios = [['all', 'All channels'], ['none', 'None – status pages only'], ['selected', 'Selected channels:']].map(([value, label]) =>
      el('label', {}, el('input', { type: 'radio', name, value, checked: notifyModes.get(monitor) === value, onchange: () => {
        notifyModes.set(monitor, value);
        monitor.notificationChannelIds = value === 'all' ? null : value === 'none' ? [] : (monitor.notificationChannelIds ?? []);
        picker.hidden = value !== 'selected';
        refresh();
      } }), label));

    referencePicker(picker,
      () => [...config.smtpChannels, ...config.webhookChannels].map((channel) => channel.id),
      () => monitor.notificationChannelIds ?? [],
      { none: 'No channels defined yet – add one below.', nothingTicked: 'Nothing ticked: this monitor will not send alerts.' },
      (id, on) => { monitor.notificationChannelIds = toggleIn(monitor.notificationChannelIds ?? [], id, on); });

    return el('fieldset', { class: 'wide' }, el('legend', {}, 'Alerts'), el('div', { class: 'choices' }, radios), picker);
  }

  function monitorCard(monitor, index) {
    const [nameField, idFieldEl] = nameAndIdFields(monitor, 'Letters, digits, "_ . -", max 64. Key of the history: renaming loses this monitor\'s past results.');
    const typeField = selectField(monitor.check, 'type', 'Check type', null, CHECK_TYPES, () => {
      monitor.check = model.newCheck(monitor.check.type);
      renderAll();
    });
    return card(monitor.name || `Monitor ${index + 1}`, config.monitors, index, 'Remove monitor',
      el('div', { class: 'grid' },
        nameField, idFieldEl,
        numberField(monitor, 'intervalSeconds', 'Interval (seconds)', 'Runs on the epoch grid: 60 = every full minute.'),
        numberField(monitor, 'timeoutSeconds', 'Timeout (seconds)', 'Includes name resolution. Must be smaller than the interval.'),
        typeField,
        ...checkFields(monitor.check),
        notificationFields(monitor)
      )
    );
  }

  // ---- channels --------------------------------------------------------------------------------

  const TLS_MODES = [
    { value: 'starttls', label: 'STARTTLS (required) – usually port 587' },
    { value: 'ssl', label: 'Implicit TLS – usually port 465' },
    { value: 'none', label: 'None – plaintext, trusted internal relays only' }
  ];

  function smtpCard(smtp, index) {
    return card(smtp.id || `SMTP channel ${index + 1}`, config.smtpChannels, index, 'Remove channel',
      el('div', { class: 'grid' },
        field('ID', idField(smtp), 'Referenced by monitors. Letters, digits, "_ . -", max 64.'),
        textField(smtp, 'host', 'SMTP host', 'e.g. smtp.example.com'),
        numberField(smtp, 'port', 'Port', '587 for STARTTLS, 465 for implicit TLS, 25 for plaintext.'),
        selectField(smtp, 'tls', 'Encryption', 'The server certificate is always verified when TLS is used.', TLS_MODES),
        textField(smtp, 'username', 'Username', 'Leave empty to send without SMTP AUTH.'),
        passwordField(smtp, 'password', 'Password', 'Stored in plain text in config.json.'),
        textField(smtp, 'from', 'Sender address', 'e.g. argos@example.com'),
        listField(smtp, 'to', 'Recipients', 'One address per line (or comma-separated).'),
        checkboxField(smtp, 'systemEvents', 'System events', 'Also receive "Argos started", "unexpected offline period" and "scheduler paused".')
      )
    );
  }

  function webhookCard(webhook, index) {
    return card(webhook.id || `Webhook channel ${index + 1}`, config.webhookChannels, index, 'Remove channel',
      el('div', { class: 'grid' },
        field('ID', idField(webhook), 'Referenced by monitors. Letters, digits, "_ . -", max 64.'),
        textField(webhook, 'method', 'Method', 'Usually POST.'),
        textField(webhook, 'url', 'URL', 'e.g. https://hooks.slack.com/services/…', { type: 'url', className: 'wide' }),
        headersEditor(webhook, 'With "Content-Type: application/json" every placeholder value is JSON-escaped.'),
        field('Body template', el('textarea', { value: webhook.bodyTemplate, oninput: (event) => { webhook.bodyTemplate = event.target.value; refresh(); } }),
          'Placeholders: {{status}} (DOWN/UP/SYSTEM), {{subject}}, {{body}}, {{monitorId}}, {{monitorName}}. Example for Slack: {"text": "*{{subject}}*\\n{{body}}"}', 'wide'),
        checkboxField(webhook, 'systemEvents', 'System events', 'Also receive "Argos started", "unexpected offline period" and "scheduler paused".')
      )
    );
  }

  // ---- status pages ----------------------------------------------------------------------------

  function pageCard(page, index) {
    const [nameField, idFieldEl] = nameAndIdFields(page, 'Becomes the path /status/<id>. Letters, digits, "_ . -", max 64.');
    const monitors = referencePicker(el('div', { class: 'choices' }),
      () => config.monitors.map((monitor) => monitor.id),
      () => page.monitorIds,
      { none: 'No monitors defined yet.', nothingTicked: 'Nothing ticked: the page will be empty.' },
      (id, on) => { page.monitorIds = toggleIn(page.monitorIds, id, on); });

    const authToggle = el('input', { type: 'checkbox', checked: page.basicAuth !== null, onchange: (event) => {
      page.basicAuth = event.target.checked ? { username: '', passwordHash: '' } : null;
      renderAll();
    } });
    const authFields = page.basicAuth === null ? [] : [
      textField(page.basicAuth, 'username', 'Username'),
      textField(page.basicAuth, 'passwordHash', 'Password hash', 'Argon2id hash, never the password itself. Generate it with:  java -jar argos.jar hashPassword=yourSecret', { className: 'wide' })
    ];

    return card(page.name || `Status page ${index + 1}`, config.statusPages, index, 'Remove page',
      el('div', { class: 'grid' },
        nameField, idFieldEl,
        el('fieldset', { class: 'wide' }, el('legend', {}, 'Monitors on this page'), monitors),
        el('fieldset', { class: 'wide' },
          el('legend', {}, 'Access'),
          el('label', { class: 'field inline' }, authToggle, el('span', {}, 'Require HTTP Basic Auth'), el('small', {}, 'Protected pages also show error details, which name internal hosts and ports.')),
          authFields.length === 0 ? null : el('div', { class: 'grid' }, ...authFields)
        )
      )
    );
  }

  // ---- general ---------------------------------------------------------------------------------

  function generalFields() {
    return el('div', { class: 'grid' },
      numberField(config, 'retentionDays', 'History retention (days)', 'Older check results are deleted daily.'),
      numberField(config, 'flappingThreshold', 'Flapping threshold', 'Consecutive failures before a DOWN alert is sent.'),
      numberField(config, 'heartbeatGapMinutesThreshold', 'Heartbeat gap (minutes)', 'A longer gap is reported as unexpected downtime or scheduler pause.'),
      textField(config, 'dataDir', 'Data directory', 'Holds argos.db. Relative to the working directory.'),
      textField(config, 'webHost', 'Bind address', 'Use 127.0.0.1 behind a reverse proxy on the same host.'),
      numberField(config, 'webPort', 'Port', 'Argos serves plain HTTP; terminate TLS in the proxy.')
    );
  }

  // ---- rendering & output ----------------------------------------------------------------------

  function renderCards(sectionId, items, cardFn) {
    $(`#${sectionId} .cards`).replaceChildren(...items.map(cardFn));
  }

  function renderAll() {
    referencePickers.length = 0;
    renderCards('monitors', config.monitors, monitorCard);
    renderCards('smtpChannels', config.smtpChannels, smtpCard);
    renderCards('webhookChannels', config.webhookChannels, webhookCard);
    renderCards('statusPages', config.statusPages, pageCard);
    $('#generalFields').replaceChildren(generalFields());
    refresh();
  }

  function refresh() {
    referencePickers.forEach((render) => render());
    const issues = model.validate(config);
    const box = $('#issues');
    box.className = issues.length === 0 ? 'ok' : 'error';
    box.replaceChildren(issues.length === 0
      ? 'No problems found – the file below is ready for Argos.'
      : el('div', {}, `${issues.length} ${issues.length === 1 ? 'problem' : 'problems'} to fix before the file can be downloaded:`, el('ul', {}, issues.map((issue) => el('li', {}, issue)))));
    $('#json').value = model.serialize(config);
    $('#download').disabled = issues.length > 0;
    $('#copy').disabled = issues.length > 0;
  }

  // ---- toolbar ---------------------------------------------------------------------------------

  const factories = {
    monitor: () => { const monitor = model.newMonitor(); autoIds.add(monitor); config.monitors.push(monitor); return 'monitors'; },
    smtp: () => { config.smtpChannels.push(model.newSmtpChannel()); return 'smtpChannels'; },
    webhook: () => { config.webhookChannels.push(model.newWebhookChannel()); return 'webhookChannels'; },
    page: () => { const page = model.newStatusPage(); autoIds.add(page); config.statusPages.push(page); return 'statusPages'; }
  };

  document.querySelectorAll('[data-add]').forEach((button) => button.addEventListener('click', () => {
    const sectionId = factories[button.dataset.add]();
    renderAll();
    const firstInput = $(`#${sectionId} .card:last-of-type input`);
    firstInput.scrollIntoView({ block: 'center' });
    firstInput.focus();
  }));

  function setFileStatus(text, isError = false) {
    const status = $('#fileStatus');
    status.textContent = text;
    status.className = isError ? 'error' : 'muted';
  }

  function applyConfig(text, sourceLabel) {
    let loaded;
    try {
      loaded = model.normalize(JSON.parse(text));
    } catch (error) {
      const reason = error instanceof SyntaxError ? `is not valid JSON: ${error.message}` : `does not look like an Argos config: ${error.message}`;
      setFileStatus(`${sourceLabel} ${reason}`, true);
      return { ok: false, reason: `${sourceLabel} ${reason}` };
    }
    config = loaded;
    notifyModes = new WeakMap();
    setFileStatus(`Loaded ${sourceLabel} – the file stays on this device.`);
    renderAll();
    return { ok: true };
  }

  $('#upload').addEventListener('change', (event) => {
    const file = event.target.files[0];
    event.target.value = '';
    if (file === undefined) return;
    file.text().then((text) => {
      applyConfig(text, file.name);
    }, (error) => setFileStatus(`${file.name} could not be read: ${error.message}`, true));
  });

  function openPasteDialog(initialText = '', initialError = '') {
    const dialog = $('#pasteDialog');
    if (!dialog) return;
    const textarea = $('#pasteInput');
    const errorEl = $('#pasteError');
    textarea.value = initialText;
    if (initialError) {
      errorEl.textContent = initialError;
      errorEl.hidden = false;
    } else {
      errorEl.textContent = '';
      errorEl.hidden = true;
    }
    dialog.showModal();
    textarea.focus();
    if (initialText) textarea.select();
  }

  $('#paste')?.addEventListener('click', async () => {
    if (navigator.clipboard && navigator.clipboard.readText) {
      try {
        const text = await navigator.clipboard.readText();
        if (text && text.trim() !== '') {
          const result = applyConfig(text, 'from clipboard');
          if (result.ok) return;
          openPasteDialog(text, result.reason);
          return;
        }
      } catch {
        // Clipboard read permission denied or not supported in this context -> fallback to dialog
      }
    }
    openPasteDialog();
  });

  const pasteDialog = $('#pasteDialog');
  if (pasteDialog) {
    $('#pasteSubmit').addEventListener('click', () => {
      const text = $('#pasteInput').value.trim();
      if (text === '') {
        const err = $('#pasteError');
        err.textContent = 'Please enter or paste JSON text.';
        err.hidden = false;
        return;
      }
      const result = applyConfig(text, 'from clipboard');
      if (result.ok) {
        pasteDialog.close();
      } else {
        const err = $('#pasteError');
        err.textContent = result.reason;
        err.hidden = false;
      }
    });

    $('#pasteCancel').addEventListener('click', () => {
      pasteDialog.close();
    });

    pasteDialog.addEventListener('click', (event) => {
      if (event.target === pasteDialog) pasteDialog.close();
    });

    $('#pasteInput').addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        $('#pasteSubmit').click();
      }
    });

    const pasteFromClipboardBtn = $('#pasteFromClipboard');
    if (navigator.clipboard && navigator.clipboard.readText) {
      pasteFromClipboardBtn.addEventListener('click', async () => {
        try {
          const text = await navigator.clipboard.readText();
          if (text) {
            $('#pasteInput').value = text;
            $('#pasteError').hidden = true;
          }
        } catch {
          const err = $('#pasteError');
          err.textContent = 'Clipboard access was denied by browser. Please paste using Ctrl+V.';
          err.hidden = false;
        }
      });
    } else {
      pasteFromClipboardBtn.hidden = true;
    }
  }

  window.addEventListener('paste', (event) => {
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)) return;
    if ($('#pasteDialog')?.open) return;
    const text = event.clipboardData?.getData('text');
    if (!text || text.trim() === '') return;
    event.preventDefault();
    const result = applyConfig(text, 'from clipboard');
    if (!result.ok) {
      openPasteDialog(text, result.reason);
    }
  });

  $('#reset').addEventListener('click', () => {
    const confirmed = confirm('Discard the current configuration and start from scratch?');
    if (confirmed === false) return;
    config = model.normalize({});
    notifyModes = new WeakMap();
    setFileStatus('');
    renderAll();
  });

  $('#download').addEventListener('click', () => {
    const url = URL.createObjectURL(new Blob([$('#json').value], { type: 'application/json' }));
    const link = el('a', { href: url, download: 'config.json' });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });

  $('#copy').addEventListener('click', async () => {
    const text = $('#json').value;
    // navigator.clipboard only exists in secure contexts; plain http on a server needs the selection fallback
    let copied = navigator.clipboard ? await navigator.clipboard.writeText(text).then(() => true, () => false) : false;
    if (copied === false) {
      $('#json').select();
      copied = document.execCommand('copy');
    }
    $('#copyStatus').textContent = copied ? 'Copied.' : 'Copying failed – select the text and copy it manually.';
    setTimeout(() => { $('#copyStatus').textContent = ''; }, 4000);
  });

  renderAll();
})();
