// Restore before first paint; storage restrictions must not prevent theme switching.
function applyStarweaveTheme(theme){
  var dark=theme==='dark';
  document.documentElement.dataset.theme=dark?'dark':'light';
  var button=document.getElementById('themeToggle');
  if(button){
    button.setAttribute('aria-pressed',String(dark));
    button.title=dark?'切换到明亮主题':'切换到星空黑夜主题';
    button.querySelector('.material-icons').textContent=dark?'light_mode':'dark_mode';
    button.querySelector('.btn-text').textContent=dark?'明亮主题':'黑夜主题';
  }
  if(typeof fileLinkPreview!=='undefined'&&fileLinkPreview.data
      &&fileLinkPreview.data.renderMode==='HTML'&&fileLinkPreview.mode==='PREVIEW'
      &&typeof renderFileLinkPreview==='function')renderFileLinkPreview();
}
function toggleStarweaveTheme(){
  var theme=document.documentElement.dataset.theme==='dark'?'light':'dark';
  applyStarweaveTheme(theme);
  try{localStorage.setItem('starweave.theme',theme)}catch(e){}
}
(function(){
  var theme='light';
  try{if(localStorage.getItem('starweave.theme')==='dark')theme='dark'}catch(e){}
  applyStarweaveTheme(theme);
  document.addEventListener('DOMContentLoaded',function(){applyStarweaveTheme(document.documentElement.dataset.theme)});
  window.addEventListener('storage',function(event){if(event.key==='starweave.theme'||event.key===null)applyStarweaveTheme(event.newValue==='dark'?'dark':'light')});
})();
