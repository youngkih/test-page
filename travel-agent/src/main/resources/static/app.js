// 가족 여행 에이전트 - 브라우저 쪽 코드 (프레임워크 없이 순수 JS)
const SESSION_KEY = 'travel-agent-session';

const $messages = document.getElementById('messages');
const $status = document.getElementById('status');
const $form = document.getElementById('composer');
const $input = document.getElementById('input');
const $send = document.getElementById('send');
const $activity = document.getElementById('activity-log');

let sessionId = null;
let busy = true; // 세션 준비가 끝날 때까지 전송 막기
$send.disabled = true;

// ---------- 세션 ----------

function loadSavedSessionId() {
  try { return localStorage.getItem(SESSION_KEY); } catch { return null; }
}

function saveSessionId(id) {
  try { localStorage.setItem(SESSION_KEY, id); } catch { /* 저장 불가 환경은 무시 */ }
}

async function createSession() {
  const res = await fetch('api/sessions', { method: 'POST' });
  const body = await res.json();
  sessionId = body.sessionId;
  saveSessionId(sessionId);
}

async function restoreSession() {
  const saved = loadSavedSessionId();
  if (saved) {
    const res = await fetch(`api/sessions/${encodeURIComponent(saved)}`);
    if (res.ok) {
      const state = await res.json();
      sessionId = state.sessionId;
      for (const line of state.transcript) {
        if (line.role === 'user') addUserMessage(line.text);
        else addAssistantMessage().setMarkdown(line.text);
      }
      if (state.itinerary) renderItinerary(state.itinerary);
      if (state.comparison) renderComparison(state.comparison);
      return;
    }
  }
  await createSession();
}

document.getElementById('new-chat').addEventListener('click', async () => {
  if (busy) return;
  if (!confirm('새 대화를 시작할까요? 현재 대화 내용은 화면에서 사라집니다.')) return;
  saveSessionId('');
  location.reload();
});

// ---------- 메시지 표시 ----------

function scrollToBottom() {
  $messages.scrollTop = $messages.scrollHeight;
}

function addUserMessage(text) {
  const div = document.createElement('div');
  div.className = 'msg user';
  div.textContent = text;
  $messages.appendChild(div);
  scrollToBottom();
}

function renderMarkdown(markdown) {
  // 라이브러리(CDN)를 못 불러오면 마크다운 없이 글자 그대로 보여준다. 정화(DOMPurify) 없이 HTML 로 넣지 않는다.
  if (!window.marked || !window.DOMPurify) {
    const escaped = markdown.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    return `<div style="white-space: pre-wrap">${escaped}</div>`;
  }
  return DOMPurify.sanitize(marked.parse(markdown));
}

/** 스트리밍으로 채워지는 어시스턴트 말풍선 */
function addAssistantMessage() {
  const div = document.createElement('div');
  div.className = 'msg assistant';
  const thinking = document.createElement('details');
  thinking.className = 'thinking';
  thinking.hidden = true;
  thinking.innerHTML = '<summary>생각 과정 보기</summary><div></div>';
  const body = document.createElement('div');
  div.append(thinking, body);
  $messages.appendChild(div);

  let text = '';
  let thoughts = '';
  return {
    appendText(delta) {
      text += delta;
      body.innerHTML = renderMarkdown(text);
      openLinksInNewTab(body);
      scrollToBottom();
    },
    appendThinking(delta) {
      thoughts += delta;
      thinking.hidden = false;
      thinking.querySelector('div').textContent = thoughts;
    },
    setMarkdown(markdown) {
      text = markdown;
      body.innerHTML = renderMarkdown(text);
      openLinksInNewTab(body);
    },
    isEmpty() { return text.trim() === ''; },
    remove() { div.remove(); },
  };
}

function addErrorMessage(text) {
  const div = document.createElement('div');
  div.className = 'msg error';
  div.textContent = '⚠️ ' + text;
  $messages.appendChild(div);
  scrollToBottom();
}

function openLinksInNewTab(root) {
  root.querySelectorAll('a').forEach(a => { a.target = '_blank'; a.rel = 'noopener noreferrer'; });
}

function setStatus(text) {
  $status.hidden = !text;
  $status.textContent = text || '';
  if (text) {
    const li = document.createElement('li');
    li.textContent = `${new Date().toLocaleTimeString('ko-KR')} ${text}`;
    $activity.appendChild(li);
  }
}

// ---------- SSE 스트림 읽기 ----------
// EventSource 는 GET 만 지원하므로, fetch 로 POST 하고 응답 본문을 직접 파싱한다.

async function* readSseEvents(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary;
    while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
      let event = 'message';
      const dataLines = [];
      for (const line of raw.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
      }
      if (dataLines.length) yield { event, data: JSON.parse(dataLines.join('\n')) };
    }
  }
}

async function sendMessage(text) {
  busy = true;
  $send.disabled = true;
  addUserMessage(text);
  const bubble = addAssistantMessage();
  setStatus('🤔 요청을 이해하는 중...');

  try {
    const res = await fetch(`api/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: text }),
    });
    if (res.status === 404) {
      await createSession();
      throw new Error('대화가 만료되어 새 대화를 시작했습니다. 메시지를 다시 보내 주세요.');
    }
    if (!res.ok) throw new Error(`서버 오류 (HTTP ${res.status})`);

    for await (const { event, data } of readSseEvents(res)) {
      switch (event) {
        case 'text': bubble.appendText(data.text); break;
        case 'thinking': bubble.appendThinking(data.text); break;
        case 'status': setStatus(data.text); break;
        case 'itinerary': renderItinerary(data); selectTab('itinerary'); break;
        case 'comparison': renderComparison(data); selectTab('comparison'); break;
        case 'error': addErrorMessage(data.text); break;
        case 'done': break;
      }
    }
  } catch (e) {
    addErrorMessage(e.message);
  } finally {
    if (bubble.isEmpty()) bubble.remove();
    setStatus('');
    busy = false;
    $send.disabled = false;
    $input.focus();
  }
}

$form.addEventListener('submit', e => {
  e.preventDefault();
  const text = $input.value.trim();
  if (!text || busy) return;
  $input.value = '';
  sendMessage(text);
});

$input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    $form.requestSubmit();
  }
});

// ---------- 탭 ----------

function selectTab(name) {
  document.querySelectorAll('.tabs button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === name)));
  document.querySelectorAll('.tab-body').forEach(el => { el.hidden = el.id !== `tab-${name}`; });
}

document.querySelectorAll('.tabs button').forEach(b => b.addEventListener('click', () => selectTab(b.dataset.tab)));

// ---------- 일정표 / 가격 비교 렌더링 ----------
// 모델이 만든 문자열은 반드시 textContent 로 넣어 HTML 주입을 막는다.

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null && text !== '') node.textContent = text;
  return node;
}

function safeLink(url, label) {
  if (!/^https?:\/\//i.test(url || '')) return document.createTextNode(label || '');
  const a = el('a', null, label || url);
  a.href = url;
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  return a;
}

const CATEGORY_ICON = {
  flight: '✈️', transport: '🚃', lodging: '🏨', meal: '🍽️', kids_play: '🛝',
  shopping: '🛍️', sightseeing: '📸', rest: '😴', other: '📌',
};

function formatDate(iso) {
  const d = new Date(iso + 'T00:00:00');
  return isNaN(d) ? iso : d.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });
}

function renderItinerary(it) {
  const root = document.getElementById('tab-itinerary');
  root.replaceChildren();
  const wrap = el('div', 'itinerary');
  wrap.append(el('h2', null, it.title), el('p', 'summary', it.summary));

  for (const day of it.days || []) {
    const card = el('section', 'day');
    card.append(el('h3', null, `${formatDate(day.date)} · ${day.theme || ''}`));
    for (const item of day.items || []) {
      const row = el('div', `item ${item.category}`);
      row.append(el('div', 'time', `${item.start}–${item.end}`));
      const body = el('div');
      body.append(el('div', 'title', `${CATEGORY_ICON[item.category] || '📌'} ${item.title}`));
      if (item.place) {
        const place = el('div', 'meta');
        place.append('📍 ', item.maps_url ? safeLink(item.maps_url, item.place) : item.place);
        body.append(place);
      }
      if (item.how) body.append(el('div', 'meta', `🚶 ${item.how}`));
      if (item.kid_notes) body.append(el('div', 'meta', `👶 ${item.kid_notes}`));
      if (item.closed_days) body.append(el('div', 'meta closed', `🗓️ 휴무: ${item.closed_days}`));
      row.append(body);
      card.append(row);
    }
    wrap.append(card);
  }

  if (it.checklist?.length) {
    wrap.append(el('h3', null, '✅ 출발 전 체크리스트'));
    const ul = el('ul', 'checklist');
    it.checklist.forEach(c => ul.append(el('li', null, c)));
    wrap.append(ul);
  }
  root.append(wrap);
}

const won = n => `${Math.round(n).toLocaleString('ko-KR')}원`;

function renderComparison(cmp) {
  const root = document.getElementById('tab-comparison');
  root.replaceChildren();
  const wrap = el('div', 'comparison');
  wrap.append(el('h2', null, cmp.title));

  const cheapest = new Map((cmp.bestByOption || []).map(b => [b.option, b]));
  const table = el('table');
  const head = el('tr');
  ['상품', '사이트', '총액(3인)', '표기 가격', '조건', '확인일'].forEach(h => head.append(el('th', null, h)));
  table.append(head);

  for (const q of cmp.quotes || []) {
    const best = cheapest.get(q.option);
    const isCheapest = best && best.siteCount > 1 && best.cheapestSite === q.site && best.cheapestKrw === q.total_price_krw;
    const tr = el('tr', isCheapest ? 'cheapest' : null);
    tr.append(el('td', null, q.option));
    const site = el('td');
    site.append(safeLink(q.url, q.site));
    if (isCheapest) site.append(el('span', 'badge', '최저가'));
    tr.append(site, el('td', 'price', won(q.total_price_krw)), el('td', null, q.original_price),
      el('td', null, q.notes), el('td', null, q.checked_at));
    table.append(tr);
  }
  const tableWrap = el('div', 'table-wrap');
  tableWrap.append(table);
  wrap.append(tableWrap);

  if (cmp.recommendation) {
    const rec = el('div', 'recommendation');
    rec.append(el('strong', null, '👍 추천: '), document.createTextNode(cmp.recommendation));
    wrap.append(rec);
  }
  wrap.append(el('p', 'hint', '※ 웹에서 조사한 시점의 참고 가격입니다. 예약 전 링크에서 최종 가격을 꼭 확인하세요.'));
  root.append(wrap);
}

restoreSession()
  .then(() => { busy = false; $send.disabled = false; })
  .catch(e => addErrorMessage('서버에 연결할 수 없습니다: ' + e.message));
