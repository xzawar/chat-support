/**
 * Minimal in-memory stand-ins for express, firebase-admin and jsonwebtoken.
 *
 * Why these exist: the point of this suite is to exercise the *real* route modules —
 * src/routes/websites.js, src/routes/widget.js, src/middleware/auth.js — rather than a retyped
 * copy of their logic, and those modules require() express and firebase-admin at import time. The
 * fakes are installed with a require hook (see harness.js) so the routes load unmodified.
 *
 * These are not a Firestore emulator and do not pretend to be. They implement exactly the query
 * shapes the routes under test use, and the transaction fake does a real read-version check with
 * retries so the "two Register taps at once" case is a genuine concurrency test rather than two
 * sequential calls.
 */

const crypto = require("crypto")

// --------------------------------------------------------------------------- express

function pathToRegex(pattern) {
  const names = []
  const source = pattern
    .split("/")
    .map((segment) => {
      if (segment.startsWith(":")) {
        names.push(segment.slice(1))
        return "([^/]+)"
      }
      return segment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
    })
    .join("/")
  return { regex: new RegExp(`^${source}/?$`), names }
}

function Router() {
  const layers = []

  const router = function router(req, res, next) {
    runLayers(layers, req, res, next)
  }

  const add = (method, args) => {
    const path = typeof args[0] === "string" ? args[0] : "/"
    const handlers = (typeof args[0] === "string" ? args.slice(1) : args).flat()
    layers.push({ method, path, handlers, ...pathToRegex(path) })
    return router
  }

  router.use = (...args) => add(null, args)
  for (const method of ["get", "post", "put", "patch", "delete"]) {
    router[method] = (...args) => add(method, args)
  }
  router.__layers = layers
  return router
}

function runLayers(layers, req, res, done) {
  let index = 0
  const basePath = req.path

  const next = (err) => {
    const layer = layers[index++]
    if (!layer) return done(err)

    const isMount = layer.method === null
    let params = {}

    if (isMount) {
      if (layer.path !== "/" && !basePath.startsWith(layer.path)) return next(err)
      // Express strips the mount path before the nested router sees the request, and the routes
      // under test declare paths relative to their mount, so the shim has to do the same.
      const remainder = layer.path === "/" ? basePath : basePath.slice(layer.path.length) || "/"
      req.params = { ...req.params }
      req.path = remainder
      return runHandlers(layer.handlers, req, res, (mountErr) => {
        req.path = basePath
        next(mountErr)
      }, err)
    } else {
      if (err) return next(err)
      if (layer.method !== req.method.toLowerCase()) return next()
      const remainder = basePath === "" ? "/" : basePath
      const match = layer.regex.exec(remainder)
      if (!match) return next()
      layer.names.forEach((name, i) => {
        params[name] = decodeURIComponent(match[i + 1])
      })
    }

    req.params = { ...req.params, ...params }
    runHandlers(layer.handlers, req, res, next, err)
  }

  next()
}

function runHandlers(handlers, req, res, outerNext, err) {
  let index = 0
  const step = (stepErr) => {
    const handler = handlers[index++]
    if (!handler) return outerNext(stepErr)
    const isErrorHandler = handler.length >= 4
    if (stepErr && !isErrorHandler) return step(stepErr)
    if (!stepErr && isErrorHandler) return step()
    try {
      if (isErrorHandler) return handler(stepErr, req, res, step)
      return handler(req, res, step)
    } catch (thrown) {
      return step(thrown)
    }
  }
  step(err)
}

function express() {
  const app = Router()
  /** Dispatches one request and resolves with { status, body }. */
  app.__handle = (request) =>
    new Promise((resolve, reject) => {
      const headers = {}
      for (const [key, value] of Object.entries(request.headers || {})) {
        headers[key.toLowerCase()] = value
      }
      const req = {
        method: (request.method || "GET").toUpperCase(),
        path: request.path,
        originalUrl: request.path,
        url: request.path,
        body: request.body || {},
        query: request.query || {},
        params: {},
        headers,
        get: (name) => headers[String(name).toLowerCase()] || undefined,
      }
      let status = 200
      const res = {
        status(code) {
          status = code
          return res
        },
        json(payload) {
          resolve({ status, body: payload })
          return res
        },
      }
      runLayers(app.__layers, req, res, (err) => {
        if (err) reject(err)
        else resolve({ status: 404, body: { error: { code: "no_route" } } })
      })
    })
  return app
}
express.Router = Router
express.json = () => (req, res, next) => next()

// --------------------------------------------------------------------------- firestore

const SERVER_TIMESTAMP = { __sentinel: "serverTimestamp" }

function timestampFor(millis) {
  return { toMillis: () => millis, toDate: () => new Date(millis) }
}

function resolveSentinels(value, existing) {
  if (value === SERVER_TIMESTAMP) return timestampFor(Date.now())
  if (value && value.__sentinel === "increment") {
    return (typeof existing === "number" ? existing : 0) + value.by
  }
  if (value && value.__sentinel === "arrayUnion") {
    const base = Array.isArray(existing) ? existing.slice() : []
    for (const entry of value.values) if (!base.includes(entry)) base.push(entry)
    return base
  }
  return value
}

function applyData(target, data, merge) {
  const next = merge ? { ...target } : {}
  for (const [key, value] of Object.entries(data)) {
    next[key] = resolveSentinels(value, target ? target[key] : undefined)
  }
  return next
}

class FakeFirestore {
  constructor() {
    this.docs = new Map()
    this.version = 0
  }

  /** Test seam: put a document in place without going through a route. */
  seed(path, data) {
    this.docs.set(path, { ...data })
    this.version += 1
  }

  peek(path) {
    const data = this.docs.get(path)
    return data ? { ...data } : null
  }

  collection(id) {
    return new CollectionRef(this, id)
  }

  collectionGroup(id) {
    return new Query(this, { collectionId: id })
  }

  batch() {
    const ops = []
    return {
      set: (ref, data, options) => ops.push(() => ref.set(data, options)),
      update: (ref, data) => ops.push(() => ref.update(data)),
      delete: (ref) => ops.push(() => ref.delete()),
      commit: async () => {
        for (const op of ops) await op()
      },
    }
  }

  /**
   * Reads are recorded with the store version they saw; if anything in the store changed before
   * the writes are applied, the whole body is retried. That is the property the one-website check
   * depends on, so it is modelled rather than assumed.
   */
  async runTransaction(body) {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const startVersion = this.version
      const writes = []
      const tx = {
        get: async (target) => target.get(),
        set: (ref, data, options) => writes.push(() => ref.set(data, options)),
        update: (ref, data) => writes.push(() => ref.update(data)),
        delete: (ref) => writes.push(() => ref.delete()),
      }
      const result = await body(tx)
      if (this.version !== startVersion) continue // contended: retry with fresh reads
      for (const write of writes) await write()
      return result
    }
    throw new Error("transaction failed after 5 attempts")
  }
}

class CollectionRef {
  constructor(store, path) {
    this.store = store
    this.path = path
  }

  get id() {
    return this.path.split("/").pop()
  }

  doc(id) {
    return new DocRef(this.store, `${this.path}/${id}`)
  }

  where(field, op, value) {
    return new Query(this.store, { prefix: this.path }).where(field, op, value)
  }

  orderBy(field, direction) {
    return new Query(this.store, { prefix: this.path }).orderBy(field, direction)
  }

  limit(n) {
    return new Query(this.store, { prefix: this.path }).limit(n)
  }

  get() {
    return new Query(this.store, { prefix: this.path }).get()
  }
}

class DocRef {
  constructor(store, path) {
    this.store = store
    this.path = path
  }

  get id() {
    return this.path.split("/").pop()
  }

  get parent() {
    const segments = this.path.split("/")
    segments.pop()
    const collectionPath = segments.join("/")
    const collection = new CollectionRef(this.store, collectionPath)
    const parentSegments = collectionPath.split("/")
    parentSegments.pop()
    collection.parent =
      parentSegments.length > 0 ? new DocRef(this.store, parentSegments.join("/")) : null
    return collection
  }

  collection(id) {
    return new CollectionRef(this.store, `${this.path}/${id}`)
  }

  async get() {
    const data = this.store.docs.get(this.path)
    return snapshotFor(this, data)
  }

  async set(data, options) {
    const merge = Boolean(options && options.merge)
    const existing = this.store.docs.get(this.path)
    this.store.docs.set(this.path, applyData(existing || {}, data, merge))
    this.store.version += 1
  }

  async update(data) {
    const existing = this.store.docs.get(this.path)
    if (!existing) throw new Error(`No document to update at ${this.path}`)
    this.store.docs.set(this.path, applyData(existing, data, true))
    this.store.version += 1
  }

  async delete() {
    this.store.docs.delete(this.path)
    this.store.version += 1
  }
}

function snapshotFor(ref, data) {
  return {
    id: ref.id,
    ref,
    exists: data !== undefined,
    data: () => (data === undefined ? undefined : { ...data }),
  }
}

class Query {
  constructor(store, options, filters = [], order = null, max = null) {
    this.store = store
    this.options = options
    this.filters = filters
    this.order = order
    this.max = max
  }

  where(field, op, value) {
    return new Query(
      this.store,
      this.options,
      [...this.filters, { field, op, value }],
      this.order,
      this.max,
    )
  }

  orderBy(field, direction) {
    return new Query(this.store, this.options, this.filters, { field, direction }, this.max)
  }

  limit(n) {
    return new Query(this.store, this.options, this.filters, this.order, n)
  }

  matchesPath(path) {
    const segments = path.split("/")
    if (this.options.prefix) {
      return path.startsWith(`${this.options.prefix}/`) &&
        segments.length === this.options.prefix.split("/").length + 1
    }
    // collectionGroup: the second-to-last segment is the collection id
    return segments.length >= 2 && segments[segments.length - 2] === this.options.collectionId
  }

  async get() {
    let entries = []
    for (const [path, data] of this.store.docs.entries()) {
      if (!this.matchesPath(path)) continue
      const ok = this.filters.every(({ field, op, value }) => {
        const actual = data[field]
        if (op === "==") return actual === value
        if (op === "!=") return actual !== value
        if (op === "<") return actual < value
        if (op === "<=") return actual <= value
        if (op === ">") return actual > value
        if (op === ">=") return actual >= value
        throw new Error(`Unsupported operator ${op}`)
      })
      if (ok) entries.push([path, data])
    }

    if (this.order) {
      const { field, direction } = this.order
      entries.sort((a, b) => {
        const left = a[1][field]
        const right = b[1][field]
        const lv = left && left.toMillis ? left.toMillis() : left
        const rv = right && right.toMillis ? right.toMillis() : right
        if (lv === rv) return 0
        const result = lv > rv ? 1 : -1
        return direction === "desc" ? -result : result
      })
    }

    if (this.max !== null) entries = entries.slice(0, this.max)

    const docs = entries.map(([path, data]) => snapshotFor(new DocRef(this.store, path), data))
    return {
      empty: docs.length === 0,
      size: docs.length,
      docs,
      forEach: (fn) => docs.forEach(fn),
    }
  }
}

// --------------------------------------------------------------------------- rtdb / auth

class FakeDatabase {
  constructor() {
    this.nodes = new Map()
  }

  ref(path) {
    const store = this
    const key = String(path).replace(/^\/+|\/+$/g, "")
    return {
      path: key,
      async get() {
        const value = store.nodes.get(key)
        return {
          exists: () => value !== undefined,
          val: () => (value === undefined ? null : value),
        }
      },
      async set(value) {
        store.nodes.set(key, value)
      },
      async update(values) {
        for (const [childPath, value] of Object.entries(values)) {
          const full = childPath.startsWith("/")
            ? childPath.slice(1)
            : key
              ? `${key}/${childPath}`
              : childPath
          store.nodes.set(full.replace(/^\/+/, ""), value)
        }
      },
      async remove() {
        store.nodes.delete(key)
      },
    }
  }
}

class FakeAuth {
  constructor() {
    this.tokens = new Map()
    this.claims = new Map()
  }

  /** Test seam: mint a token string that verifyIdToken will accept. */
  issueIdToken(uid, payload) {
    const token = `id_${crypto.randomBytes(8).toString("hex")}`
    this.tokens.set(token, { uid, ...payload })
    return token
  }

  async verifyIdToken(token) {
    const decoded = this.tokens.get(token)
    if (!decoded) throw new Error("invalid id token")
    const claims = this.claims.get(decoded.uid) || {}
    return { ...claims, ...decoded }
  }

  async setCustomUserClaims(uid, claims) {
    this.claims.set(uid, claims)
  }

  async createCustomToken(uid, claims) {
    return `custom_${uid}_${Buffer.from(JSON.stringify(claims)).toString("base64")}`
  }
}

// --------------------------------------------------------------------------- admin

function createAdmin() {
  const store = new FakeFirestore()
  const database = new FakeDatabase()
  const authInstance = new FakeAuth()
  const sent = []

  const firestoreFn = () => store
  firestoreFn.FieldValue = {
    serverTimestamp: () => SERVER_TIMESTAMP,
    increment: (by) => ({ __sentinel: "increment", by }),
    arrayUnion: (...values) => ({ __sentinel: "arrayUnion", values }),
    delete: () => ({ __sentinel: "delete" }),
  }
  firestoreFn.Timestamp = {
    now: () => timestampFor(Date.now()),
    fromMillis: (ms) => timestampFor(ms),
  }

  return {
    apps: [{ name: "[DEFAULT]" }],
    initializeApp: () => {},
    credential: { applicationDefault: () => ({}) },
    firestore: firestoreFn,
    database: () => database,
    auth: () => authInstance,
    messaging: () => ({
      sendEachForMulticast: async ({ tokens }) => {
        sent.push(tokens)
        return { successCount: tokens.length, responses: tokens.map(() => ({ success: true })) }
      },
    }),
    __store: store,
    __database: database,
    __auth: authInstance,
    __sent: sent,
  }
}

// --------------------------------------------------------------------------- jsonwebtoken

function createJwt() {
  return {
    sign(payload, secret, options) {
      const body = { ...payload, sub: options && options.subject, __secret: secret }
      return Buffer.from(JSON.stringify(body)).toString("base64url")
    },
    verify(token, secret) {
      const body = JSON.parse(Buffer.from(token, "base64url").toString("utf8"))
      if (body.__secret !== secret) throw new Error("invalid signature")
      delete body.__secret
      return body
    },
  }
}

module.exports = { express, createAdmin, createJwt, SERVER_TIMESTAMP, timestampFor }
