// 从 core/amd 导入
export { hasModule, modDefine, modRequire } from './core/amd'

// 从 core/callback 导入
export { default as callback } from './core/callback'

export {
	CANVAS_ACTIVE_PROP,
	CANVAS_CONTRACT_CHANGE_EVENT,
	CANVAS_NODE_PROP,
	CANVAS_OWNER_PROP,
} from './core/dom-contract'

export {
	CANVAS_RGBA_BYTES_PER_PIXEL,
	canvasPixelBudgetError,
	MAX_CANVAS_BITMAP_PIXELS,
	MAX_CANVAS_DIMENSION,
	MAX_CANVAS_IMAGE_BYTES,
	MAX_CANVAS_TRANSFER_PIXELS,
	normalizeCanvasBitmapDimension,
} from './core/canvas-limits'

export {
	createDataFunctionReference,
	getDataFunctionReferenceId,
	mayContainDataFunctionReferences,
	isDataFunctionReference,
	transformDataFunctions,
} from './core/data-function'

export { encodeOpsCompact, decodeOpsCompact } from './core/canvas-ops-compact'
export {
	decodeOpsBinary,
	decodeOpsBinaryEnvelope,
	encodeOpsBinary,
	OPS_BINARY_KNOWN_OPS,
	OPS_BINARY_VERSION,
	OpsBinaryUnsupportedError,
} from './core/canvas-ops-binary'

export {
	matchesPropertyType,
	normalizePropertyDefinition,
	normalizePropertyValues,
	resolvePropertyValue,
} from './core/properties'

// 从 core/utils 导入
export {
	animationToStyle,
	camelCaseToUnderscore,
	cloneDeep,
	deepEqual,
	get,
	getDataAttributes,
	isAndroid,
	isDesktop,
	isFunction,
	isHarmonyOS,
	isIOS,
	isNil,
	isString,
	isWebWorker,
	parsePath,
	set,
	sleep,
	suffixPixel,
	toCamelCase,
	transformRpx,
	uuid,
} from './core/utils'

export { arrayBufferToBase64, base64ToArrayBuffer } from './core/binary'
export { decodeCanvasRle32, inspectCanvasRle32Tail, decodeCanvasRle32Prefix, decodeCanvasRle32PrefixWithTail } from './core/canvas-compression'
export { decodeLargeCanvasRleBand, consumeLargeCanvasRleBand } from './core/canvas-decode-fast'

export { propertyBindingPathMatches, propertyBindingPaths } from './core/property-bindings'
export { LinkedOrder } from './core/linked-order'
export { fontFaceKey, HOST_FONT_PATH, normalizeFontFace, resolveFontFaceSource } from './core/font-face'
