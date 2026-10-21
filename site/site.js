// Tsukaima site: the hero's thinking-verb cycle and the approval demo. The page is complete without it.
(() => {
  const f = document.getElementById('flair');
  // From THINKING_FLAIR in design/components/chat/StatusLine.jsx.
  const V = ['Summoning…', 'Charging ki…', 'Reading the scroll…', 'Steeping matcha…', 'Folding cranes…',
    'Binding the contract…', 'Consulting the shrine…', 'Drawing the seal…', 'Sharpening the blade…', 'Channeling…'];
  if (f && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
    let i = 0;
    setInterval(() => {
      f.style.opacity = 0;
      setTimeout(() => { f.textContent = V[i++ % V.length]; f.style.opacity = 1; }, 150);
    }, 2200);
  }

  const d = document.getElementById('d-approval');
  if (!d) return;
  const card = d.querySelector('.approval'), done = d.querySelector('.approval-done');
  const msg = done && done.querySelector('.msg p'), reset = done && done.querySelector('.reset');
  const approve = card && card.querySelector('.approve'), deny = card && card.querySelector('.deny');
  if (!msg || !reset || !approve || !deny) return;
  const ok = msg.textContent;
  const answer = (denied) => {
    card.hidden = true;
    done.hidden = false;
    done.classList.toggle('denied', denied);
    msg.textContent = denied ? 'Understood — I left it running.' : ok;
    reset.focus();
  };
  approve.onclick = () => answer(false);
  deny.onclick = () => answer(true);
  reset.onclick = () => {
    done.hidden = true;
    card.hidden = false;
    done.classList.remove('denied');
    msg.textContent = ok;
    approve.focus();
  };
})();
