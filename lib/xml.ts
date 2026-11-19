import { InstallError } from "./overlay.ts";

type Node = { tag: string; attrs: [string, string][]; text: string; children: { node: Node; tail: string }[] };

const NAMED: Record<string, string> = { lt: "<", gt: ">", amp: "&", quot: '"', apos: "'" };

function entities(raw: string) {
  return raw.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);?/g, (whole, name: string) => {
    if (!whole.endsWith(";")) throw new InstallError(`malformed entity in resource XML: ${whole}`);
    if (name.startsWith("#x")) return String.fromCodePoint(parseInt(name.slice(2), 16));
    if (name.startsWith("#")) return String.fromCodePoint(parseInt(name.slice(1), 10));
    if (!(name in NAMED)) throw new InstallError(`undefined entity in resource XML: ${whole}`);
    return NAMED[name];
  });
}

function key(node: Node): unknown {
  return [node.tag, [...node.attrs].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)), node.text.trim(),
    node.children.map(c => [key(c.node), c.tail.trim()])];
}

export function resourceEntries(data: Buffer) {
  const s = data.toString("latin1");
  if (s.includes("<!DOCTYPE") || s.includes("<!ENTITY")) throw new InstallError("resource XML must not declare entities or a doctype");
  const utf8 = (a: number, b: number) => data.subarray(a, b).toString("utf-8");
  const stack: Node[] = [];
  const starts: number[] = [];
  let root: Node | null = null;
  let closing = -1;
  const top: { node: Node; raw: Buffer }[] = [];
  const addText = (text: string) => {
    const node = stack[stack.length - 1];
    if (!node) {
      if (text.trim()) throw new InstallError("text outside the resource XML root");
      return;
    }
    const last = node.children[node.children.length - 1];
    if (last) last.tail += text;
    else node.text += text;
  };
  let i = 0;
  while (i < s.length) {
    const lt = s.indexOf("<", i);
    if (lt === -1) {
      addText(entities(utf8(i, s.length)));
      break;
    }
    if (lt > i) addText(entities(utf8(i, lt)));
    if (s.startsWith("<!--", lt)) {
      const end = s.indexOf("-->", lt + 4);
      if (end === -1) throw new InstallError("unterminated comment in resource XML");
      i = end + 3;
    } else if (s.startsWith("<![CDATA[", lt)) {
      const end = s.indexOf("]]>", lt + 9);
      if (end === -1) throw new InstallError("unterminated CDATA in resource XML");
      addText(utf8(lt + 9, end));
      i = end + 3;
    } else if (s.startsWith("<?", lt)) {
      const end = s.indexOf("?>", lt + 2);
      if (end === -1) throw new InstallError("unterminated processing instruction in resource XML");
      i = end + 2;
    } else if (s.startsWith("</", lt)) {
      const end = s.indexOf(">", lt);
      const tag = s.slice(lt + 2, end).trim();
      const node = stack.pop();
      if (!node || node.tag !== tag) throw new InstallError(`mismatched tag in resource XML: ${tag}`);
      if (stack.length === 1) top.push({ node, raw: data.subarray(starts.pop()!, end + 1) });
      else starts.pop();
      if (!stack.length) closing = lt;
      i = end + 1;
    } else {
      const match = /^<([A-Za-z_][\w.:-]*)((?:\s+[\w.:-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/?)>/.exec(s.slice(lt, s.indexOf(">", lt) + 1));
      if (!match) throw new InstallError("malformed tag in resource XML");
      const attrs: [string, string][] = [];
      for (const a of match[2].matchAll(/([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) {
        attrs.push([a[1], entities(Buffer.from(a[2] ?? a[3], "latin1").toString("utf-8"))]);
      }
      const node: Node = { tag: match[1], attrs, text: "", children: [] };
      const parent = stack[stack.length - 1];
      if (parent) parent.children.push({ node, tail: "" });
      else if (root) throw new InstallError("resource XML has more than one root");
      else root = node;
      const end = lt + match[0].length;
      if (match[3]) {
        if (stack.length === 1) top.push({ node, raw: data.subarray(lt, end) });
        if (!parent) closing = lt;
      } else {
        stack.push(node);
        starts.push(lt);
      }
      i = end;
    }
  }
  if (!root || stack.length) throw new InstallError("resource XML is not well-formed");
  if (root.tag !== "resources") throw new InstallError("resource XML root must be resources");
  const entries = new Map<string, { key: string; raw: Buffer }>();
  for (const { node, raw } of top) {
    const name = node.attrs.find(a => a[0] === "name")?.[1];
    if (!name || entries.has(name)) throw new InstallError(`resource XML contains missing or duplicate name: ${name}`);
    entries.set(name, { key: JSON.stringify(key(node)), raw });
  }
  return { entries, closing };
}
