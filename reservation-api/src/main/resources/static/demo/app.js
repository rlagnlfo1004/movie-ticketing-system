(() => {
  const params = new URLSearchParams(location.search);
  const live = params.get('mode') === 'live';
  const screeningId = params.get('screeningId') || '1';
  const apiBase = params.get('apiBase') || '';
  const $ = (id) => document.getElementById(id);
  const state = {step: 'queue', queueRank: 128, token: null, selected: [], seats: new Map(), hold: null, timer: null, result: null, cancelled: 7};
  const rows = Array.from({length: 25}, (_, i) => String.fromCharCode(65 + i));

  function initialStatus(row, col) {
    const index = (row.charCodeAt(0) - 65) * 40 + col - 1;
    const slot = (index * 37 + 300) % 1000;
    if (slot < 224) return 'CONFIRMED';
    if (slot < 258) return 'HELD';
    return 'AVAILABLE';
  }

  function setStep(step) {
    state.step = step;
    const order = ['queue', 'seat', 'payment', 'done'];
    document.querySelectorAll('.journey-step').forEach((element) => {
      const position = order.indexOf(element.dataset.step);
      element.classList.toggle('active', element.dataset.step === step);
      element.classList.toggle('done', position < order.indexOf(step));
    });
    $('hold-button').hidden = step !== 'seat';
    $('pay-button').hidden = step !== 'payment';
    $('hold-timer').hidden = step !== 'payment';
    $('result-panel').hidden = step !== 'done';
    $('seat-panel').hidden = step === 'done';
    $('queue-panel').hidden = step === 'done';
    renderSummary();
  }

  function renderSeats() {
    const map = $('seat-map');
    map.replaceChildren();
    const fragment = document.createDocumentFragment();
    let held = 0;
    let confirmed = 0;
    for (const row of rows) {
      const label = document.createElement('span');
      label.className = 'row-label';
      label.textContent = row;
      fragment.appendChild(label);
      for (let col = 1; col <= 40; col++) {
        const seatNo = `${row}-${col}`;
        const status = state.seats.get(seatNo) || initialStatus(row, col);
        if (status === 'HELD') held++;
        if (status === 'CONFIRMED') confirmed++;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = `seat ${state.selected.includes(seatNo) ? 'selected' : status.toLowerCase()}`;
        button.dataset.seat = seatNo;
        button.title = `${seatNo} ${status === 'AVAILABLE' ? '선택 가능' : status === 'HELD' ? '선점' : '확정'}`;
        button.setAttribute('aria-label', button.title);
        button.disabled = status !== 'AVAILABLE' || (state.step !== 'seat' && state.step !== 'payment');
        button.addEventListener('click', () => selectSeat(seatNo));
        fragment.appendChild(button);
      }
    }
    map.appendChild(fragment);
    $('metric-remaining').textContent = String(1000 - held - confirmed);
    $('metric-held').textContent = String(held);
    $('metric-confirmed').textContent = String(confirmed);
    $('metric-cancelled').textContent = String(state.cancelled);
    $('screening-remaining').textContent = String(1000 - held - confirmed);
  }

  function renderSummary() {
    $('selected-seats').textContent = state.selected.length ? state.selected.join(' · ') : '좌석을 선택해 주세요';
    $('seat-count').textContent = `${state.selected.length}명`;
    $('total-price').textContent = `${(state.selected.length * 15000).toLocaleString('ko-KR')}원`;
    $('hold-button').disabled = state.step !== 'seat' || state.selected.length === 0;
  }

  function selectSeat(seatNo) {
    if (state.step !== 'seat') return;
    const index = state.selected.indexOf(seatNo);
    if (index >= 0) state.selected.splice(index, 1);
    else if (state.selected.length < 4) state.selected.push(seatNo);
    else return showMessage('좌석은 최대 4석까지 선택할 수 있어요.', true);
    renderSeats();
    renderSummary();
    showMessage(state.selected.length ? `${state.selected.length}석을 선택했어요.` : '좌석을 선택해 주세요.');
  }

  function showMessage(message, error = false) {
    $('booking-message').textContent = message;
    $('booking-message').classList.toggle('error', error);
  }

  function renderQueue() {
    $('queue-rank').textContent = state.queueRank;
    $('queue-ahead').textContent = `${Math.max(0, state.queueRank - 1)}명`;
    $('queue-progress').style.width = `${Math.max(8, 100 - state.queueRank / 128 * 100)}%`;
  }

  async function request(path, options = {}) {
    const response = await fetch(`${apiBase}/api/v1/${path}`, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.code || data.message || `HTTP ${response.status}`);
    return data;
  }

  async function registerQueue() {
    $('queue-button').disabled = true;
    if (live) {
      try {
        const data = await request(`screenings/${screeningId}/queue/register`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
        state.token = data.token;
        $('queue-button').textContent = '입장 대기 중';
        await pollQueue();
      } catch (error) { showMessage(error.message, true); $('queue-button').disabled = false; }
      return;
    }
    state.token = 'demo-token-001';
    $('queue-button').textContent = '입장 대기 중';
    showMessage('대기 순번을 확인하고 있어요.');
    state.queueTimer = setInterval(() => {
      state.queueRank = Math.max(1, state.queueRank - 17);
      renderQueue();
      if (state.queueRank === 1) enterSeats();
    }, 450);
  }

  async function pollQueue() {
    const data = await request(`screenings/${screeningId}/queue/status?token=${encodeURIComponent(state.token)}`);
    state.queueRank = data.rank || 1;
    renderQueue();
    if (data.status === 'ACTIVE') return enterSeats();
    if (data.status === 'NOT_FOUND') throw new Error('대기열 토큰을 찾지 못했어요.');
    state.queueTimer = setTimeout(() => pollQueue().catch((error) => showMessage(error.message, true)), data.pollAfterMillis || 3000);
  }

  async function enterSeats() {
    clearInterval(state.queueTimer);
    clearTimeout(state.queueTimer);
    state.queueRank = 1;
    renderQueue();
    $('queue-button').textContent = '입장 완료';
    $('queue-message').textContent = '좌석을 선택할 수 있어요.';
    setStep('seat');
    showMessage('마음에 드는 좌석을 선택해 주세요.');
    if (live) {
      try {
        const data = await request(`screenings/${screeningId}/seats`);
        state.seats = new Map(data.seats.map((seat) => [seat.seatNo, seat.status]));
      } catch (error) { showMessage(error.message, true); }
    }
    renderSeats();
  }

  function startTimer(until) {
    clearInterval(state.timer);
    const tick = () => {
      const seconds = Math.max(0, Math.ceil((until - Date.now()) / 1000));
      $('timer-value').textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
      if (seconds === 0) expireHold();
    };
    tick();
    state.timer = setInterval(tick, 250);
  }

  async function holdSeats() {
    if (state.step !== 'seat' || !state.selected.length) return;
    $('hold-button').disabled = true;
    try {
      if (live) {
        state.hold = await request(`screenings/${screeningId}/holds`, {
          method: 'POST',
          headers: {'Content-Type': 'application/json', 'Queue-Token': state.token, 'Idempotency-Key': crypto.randomUUID()},
          body: JSON.stringify({seats: state.selected})
        });
      } else {
        const claimed = window.demoClaimSeat ? await window.demoClaimSeat(state.selected) : {ok: state.selected.every((seat) => state.seats.get(seat) !== 'HELD')};
        if (!claimed.ok) throw new Error('SEAT_TAKEN');
        state.hold = {holdId: 'demo-hold', paymentId: 'demo-payment', amount: state.selected.length * 15000, heldUntil: new Date(Date.now() + 30000).toISOString()};
      }
      state.selected.forEach((seat) => state.seats.set(seat, 'HELD'));
      setStep('payment');
      renderSeats();
      startTimer(new Date(state.hold.heldUntil).getTime());
      showMessage('좌석을 선점했어요. 시간이 끝나기 전에 결제해 주세요.');
    } catch (error) { showMessage(error.message === 'SEAT_TAKEN' ? 'SEAT_TAKEN · 다른 사용자가 먼저 이 좌석을 선점했어요.' : error.message, true); renderSummary(); }
  }

  async function loadPortOne() {
    if (window.PortOne) return;
    await new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://cdn.portone.io/v2/browser-sdk.js';
      script.onload = resolve;
      script.onerror = () => reject(new Error('PortOne SDK를 불러오지 못했어요.'));
      document.head.appendChild(script);
    });
  }

  async function pay() {
    if (state.step !== 'payment') return;
    $('pay-button').disabled = true;
    try {
      if (live) {
        const storeId = params.get('storeId');
        const channelKey = params.get('channelKey');
        if (!storeId || !channelKey) throw new Error('실제 결제에는 storeId와 channelKey 설정이 필요해요.');
        await loadPortOne();
        const response = await window.PortOne.requestPayment({storeId, channelKey, paymentId: state.hold.paymentId,
          orderName: `루멘 시네마 ${state.selected.length}석`, totalAmount: state.hold.amount, currency: 'KRW', payMethod: 'CARD', customData: state.hold.customData});
        if (response.code) throw new Error(response.message || response.code);
        await request(`payments/${state.hold.paymentId}/complete`, {method: 'POST'});
        const mine = await request(`screenings/${screeningId}/reservations/me`, {headers: {'Queue-Token': state.token}});
        if (mine.status !== 'CONFIRMED') throw new Error(`결제 상태: ${mine.status}`);
      }
      state.selected.forEach((seat) => state.seats.set(seat, 'CONFIRMED'));
      finish('CONFIRMED');
    } catch (error) { showMessage(error.message, true); $('pay-button').disabled = false; }
  }

  function finish(result) {
    clearInterval(state.timer);
    state.result = result;
    renderSeats();
    setStep('done');
    $('result-seats').textContent = state.selected.join(' · ');
    $('result-icon').classList.toggle('warning', result !== 'CONFIRMED');
    if (result === 'CONFIRMED') { $('result-icon').textContent = '✓'; $('result-title').textContent = '예매가 완료됐어요'; $('result-copy').textContent = '좋은 자리에서 만나요.'; }
    else if (result === 'EXPIRED') { $('result-icon').textContent = '!'; $('result-title').textContent = '선점 시간이 만료됐어요'; $('result-copy').textContent = '좌석을 다시 선택해 주세요.'; }
    else { state.cancelled++; $('metric-cancelled').textContent = String(state.cancelled); $('result-icon').textContent = '!'; $('result-title').textContent = '결제가 취소됐어요'; $('result-copy').textContent = '좌석을 확정할 수 없어 결제를 취소했습니다.'; }
  }

  function expireHold() {
    if (state.step !== 'payment') return;
    state.selected.forEach((seat) => state.seats.set(seat, 'AVAILABLE'));
    finish('EXPIRED');
  }

  function restart() {
    clearInterval(state.timer);
    clearInterval(state.queueTimer);
    state.selected = []; state.hold = null; state.token = null; state.queueRank = 128;
    state.seats.clear();
    $('queue-button').disabled = false;
    $('queue-button').textContent = '대기열 등록하기 ↗';
    renderQueue(); renderSeats(); setStep('queue'); showMessage('대기열에 등록해 주세요.');
  }

  function switchView(admin) {
    $('admin-view').hidden = !admin;
    $('booking-view').hidden = admin;
    $('admin-tab').classList.toggle('active', admin);
    $('booking-tab').classList.toggle('active', !admin);
  }

  $('queue-button').addEventListener('click', registerQueue);
  $('hold-button').addEventListener('click', holdSeats);
  $('pay-button').addEventListener('click', pay);
  $('restart-button').addEventListener('click', restart);
  $('booking-tab').addEventListener('click', () => switchView(false));
  $('admin-tab').addEventListener('click', () => switchView(true));
  $('mode-pill').textContent = live ? 'LIVE API · PORTONE' : 'INTERACTIVE DEMO';
  window.demo = {enterSeats, expireHold, finish, switchView, selectSeat, holdSeats, pay, state};
  renderQueue(); renderSeats(); setStep('queue');
})();
