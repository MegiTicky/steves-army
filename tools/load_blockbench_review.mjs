import { readFileSync } from 'node:fs';

const file = process.argv[2];
const data = JSON.parse(readFileSync(file, 'utf8'));
const targets = await (await fetch('http://127.0.0.1:9222/json/list')).json();
const page = targets.find(target => target.type === 'page');
if (!page) throw new Error('Blockbench page target not found');

const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.onopen = resolve;
  socket.onerror = reject;
});

let nextId = 0;
function evaluate(expression) {
  return new Promise((resolve, reject) => {
    const id = ++nextId;
    const listener = event => {
      const message = JSON.parse(event.data);
      if (message.id !== id) return;
      socket.removeEventListener('message', listener);
      if (message.result?.exceptionDetails) {
        reject(new Error(message.result.exceptionDetails.text));
      } else {
        resolve(message.result?.result?.value);
      }
    };
    socket.addEventListener('message', listener);
    socket.send(JSON.stringify({
      id,
      method: 'Runtime.evaluate',
      params: { expression, returnByValue: true, awaitPromise: true },
    }));
  });
}

const payload = JSON.stringify(data);
const result = await evaluate(`(() => {
  const data = ${payload};
  const path = ${JSON.stringify(file.replaceAll('\\', '/'))};
  Codecs.project.load(data, {path});
  return {project: Project.name, groups: Group.all.length, cubes: Cube.all.length,
    names: Cube.all.slice(0, 8).map(cube => cube.name)};
})()`);
console.log(result);
socket.close();
