const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
(async () => {
  process.chdir(path.resolve(__dirname, '../..'));
  fs.mkdirSync('build/reports/wf', {recursive:true});
  const browser = await chromium.launch({headless:true, ...(process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {channel:'chrome'})});
  const page = await browser.newPage({viewport:{width:1024,height:1100}});
  const errors=[]; const network=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{if(!r.url().startsWith('file:'))network.push(r.url());});
  const reports=[];
  const url=pathToFileURL(path.resolve('docs/prototypes/observing-workflow.html')).href;
  await page.goto(url);
  await page.locator('details.outside-tools > summary').click();
  const snap=()=>page.evaluate(()=>window.workflowPrototype.snapshot());
  const click=(action,extra='')=>page.locator(`${action==='close-overlay'?'#app .sheet':'#app'} [data-action="${action}"]${extra}`).first().click();
  const root=(name)=>click('workspace',`[data-workspace="${name}"]`);
  const reset=()=>page.evaluate(()=>window.workflowPrototype.reset());
  const back=()=>page.keyboard.press('Escape');
  async function test(name,fn){await reset();await fn();reports.push({name,status:'passed'});console.log('PASS '+name);}
  await test('01 four workspaces and capture start',async()=>{
    assert.equal((await snap()).workspace,'capture');
    assert.equal(await page.locator('#app nav button').count(),4);
    assert.equal(await page.locator('#app .toolbar').count(),0);
    for(const name of ['sky','media','equipment']){await root(name);assert.equal((await snap()).workspace,name);await back();assert.equal((await snap()).workspace,'capture');}
  });
  await test('02 target library -> target -> guidance without mount',async()=>{
    await root('sky');await click('targets');await click('select','[data-target="M42"]');await click('start-guide');
    const s=await snap();assert.equal(s.workspace,'sky');assert.equal(s.guideTask.target.id,'M42');assert.equal(s.guideTask.phase,'missing');assert.equal(s.mountConnected,false);
    await click('guide-demo');assert.equal((await snap()).phoneReady,false);
  });
  await test('03 blank closes target card and drag keeps it',async()=>{
    await root('sky');await click('select','[data-target="M42"]');
    await page.locator('[data-sky="blank"]').click({position:{x:30,y:180}});
    assert.equal((await snap()).target.id,'M42');assert.equal((await snap()).targetCard,false);
    await click('show-card');const b=await page.locator('[data-sky="blank"]').boundingBox();
    await page.mouse.move(b.x+30,b.y+180);await page.mouse.down();await page.mouse.move(b.x+90,b.y+180);await page.mouse.up();
    assert.equal((await snap()).targetCard,true);
    await click('target-detail');await back();assert.equal((await snap()).workspace,'sky');await back();assert.equal((await snap()).targetCard,false);assert.equal((await snap()).workspace,'sky');
  });
  await test('04 preparation -> polar steps -> exact source',async()=>{
    await root('sky');await click('preparation');await click('polar');await click('polar-next');
    await back();assert.equal((await snap()).polarStep,0);assert.equal((await snap()).pages.at(-1).kind,'polar');
    await back();assert.equal((await snap()).pages.at(-1).kind,'preparation');
    await back();assert.equal((await snap()).workspace,'sky');assert.equal((await snap()).pages.length,0);
  });
  await test('05 connected wheel quick sheet keeps sky and target',async()=>{
    await root('sky');await click('select','[data-target="M42"]');await click('device','[data-device="wheel"]');
    await click('filter','[data-position="3"]');await page.locator('.scrim').click({position:{x:8,y:8}});
    const s=await snap();assert.equal(s.workspace,'sky');assert.equal(s.target.id,'M42');assert.equal(s.wheelPosition,3);assert.equal(s.overlay,null);
  });
  await test('06 connect -> same control -> original EAA',async()=>{
    await click('eaa');await click('device','[data-device="camera"]');await click('connect','[data-device="camera"]');
    assert.equal((await snap()).overlay.device,'camera');await back();assert.equal((await snap()).pages.at(-1).kind,'eaa');assert.equal((await snap()).cameraConnected,true);
  });
  await test('07 full device page returns to originating sky',async()=>{
    await root('sky');await click('device','[data-device="focuser"]');await click('full-device');assert.equal((await snap()).pages.at(-1).kind,'device-detail');await back();assert.equal((await snap()).workspace,'sky');
  });
  await test('08 EAA -> save -> view -> EAA, brightness kept',async()=>{
    await page.locator('[data-lab="cameraConnected"]').check();await click('eaa');await click('toggle-eaa');await click('sample');
    await page.locator('[data-input="brightness"]').fill('72');await click('save');await click('open-media');assert.equal((await snap()).pages.at(-1).kind,'media-detail');await back();
    assert.equal((await snap()).pages.at(-1).kind,'eaa');assert.equal((await snap()).eaa.brightness,72);assert.equal((await snap()).eaa.frames,12);
    await root('media');assert.equal(await page.locator('[data-action="open-media"]').count(),4);
  });
  await test('09 fullscreen back before task page',async()=>{
    await click('eaa');await click('fullscreen');await back();assert.equal((await snap()).fullscreen,false);assert.equal((await snap()).pages.at(-1).kind,'eaa');
  });
  await test('10 saved video -> processing -> selected video -> list',async()=>{
    await root('media');await click('open-media','[data-media="sample-video"]');await click('planetary');await back();assert.equal((await snap()).pages.at(-1).id,'sample-video');await back();assert.equal((await snap()).workspace,'media');assert.equal((await snap()).pages.length,0);
  });
  await test('11 settings back from every workspace',async()=>{
    for(const name of ['sky','capture','media','equipment']){await root(name);await click('settings');await back();assert.equal((await snap()).workspace,name);assert.equal((await snap()).pages.length,0);}
  });
  await test('12 unsaved edit confirmation, discard and keep',async()=>{
    await root('equipment');await click('optics');await page.locator('[data-input="draft"]').fill('测试光路');await back();assert.equal((await snap()).overlay.kind,'discard');
    await click('close-overlay');assert.equal((await snap()).draft,'测试光路');await back();await click('discard');assert.equal((await snap()).pages.length,0);assert.equal((await snap()).optics,'主镜 · 600 mm');
  });
  await test('13 fixed task target and resume after workspace change',async()=>{
    await root('sky');await click('select','[data-target="M42"]');await click('start-guide');await click('guide-demo');await click('targets');await click('select','[data-target="M31"]');
    assert.equal((await snap()).target.id,'M31');assert.equal((await snap()).guideTask.target.id,'M42');await root('equipment');await click('resume-guide');assert.equal((await snap()).guideTask.target.id,'M42');
  });
  await test('14 failure keeps params and can recover',async()=>{
    await page.locator('[data-lab="cameraConnected"]').check();await page.locator('[data-lab="failNext"]').check();await click('eaa');await click('toggle-eaa');assert.equal((await snap()).eaa.phase,'failed');await click('toggle-eaa');assert.equal((await snap()).eaa.phase,'running');
  });
  await test('15 STOP stays above quick device sheet',async()=>{
    await page.locator('[data-lab="mountConnected"]').check();await root('sky');await click('select','[data-target="M42"]');await click('goto');await click('confirm-goto');await click('device','[data-device="focuser"]');
    await page.locator('#app .stop').click();assert.equal((await snap()).motion,false);assert.equal((await snap()).overlay.device,'focuser');
  });
  await test('16 held focus stops on release and dismissal',async()=>{
    await click('device','[data-device="focuser"]');const b=await page.locator('[data-action="focus"][data-direction="1"]').boundingBox();await page.mouse.move(b.x+b.width/2,b.y+b.height/2);await page.mouse.down();await page.waitForTimeout(180);assert.equal((await snap()).focusMoving,true);await page.mouse.up();assert.equal((await snap()).focusMoving,false);
    await page.mouse.down();await page.keyboard.press('Escape');assert.equal((await snap()).focusMoving,false);await page.mouse.up();assert.equal((await snap()).overlay,null);
  });
  await test('17 empty media offers capture and import recovery',async()=>{
    await page.locator('[data-action="empty-media"]').click();await root('media');assert.equal(await page.locator('#app [data-action="open-media"]').count(),0);assert.equal(await page.locator('#app [data-action="import"]').count(),2);await click('workspace','[data-workspace="capture"]');assert.equal((await snap()).workspace,'capture');
  });
  await test('18 keyboard focus returns to originating device trigger',async()=>{
    await root('sky');await page.locator('.app-head [data-action="device"][data-device="wheel"]').focus();await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(()=>document.activeElement.classList.contains('sheet')),true);
    await back();assert.equal(await page.evaluate(()=>document.activeElement.dataset.device),'wheel');
    assert.equal(await page.evaluate(()=>document.activeElement.dataset.action),'device');
  });
  await root('sky');await click('select','[data-target="M42"]');await page.screenshot({path:'build/reports/wf/prototype-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:1000});await click('night');await page.screenshot({path:'build/reports/wf/prototype-mobile-red.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await click('device','[data-device="focuser"]');await page.screenshot({path:'build/reports/wf/prototype-mobile-device.png',fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  assert.deepEqual(errors,[]);assert.deepEqual(network,[]);
  fs.writeFileSync('build/reports/wf/prototype-verification.json',JSON.stringify({artifact:'docs/prototypes/observing-workflow.html',browser:'Chrome headless',desktop:'1024x1100',mobile:'390x1000 red',tests:reports,errors,network},null,2));
  console.log(JSON.stringify({passed:reports.length,errors,network,screenshots:3}));await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
