import { CommonModule } from '@angular/common';
import { Component, ElementRef, ViewChild } from '@angular/core';
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';
import * as pdfjsLib from 'pdfjs-dist';

type Tool = 'select' | 'addText' | 'replaceText' | 'addImage';
type ItemType = 'text' | 'replace-text' | 'image';

interface OverlayItem {
  id: string;
  type: ItemType;
  pageIndex: number;
  x: number;
  y: number;
  text?: string;
  fontSize?: number;
  width?: number;
  height?: number;
  imageDataUrl?: string;
}

interface DraftState {
  pdfBase64: string;
  items: OverlayItem[];
}

interface PdfJsDocument {
  numPages: number;
  getPage(pageNumber: number): Promise<PdfJsPage>;
}

interface PdfJsPage {
  getViewport(options: { scale: number }): { width: number; height: number; scale: number };
  render(options: { canvasContext: CanvasRenderingContext2D; viewport: { width: number; height: number; scale: number } }): { promise: Promise<void> };
}

interface PendingPlacement {
  x: number;
  y: number;
  pageIndex: number;
}

(pdfjsLib as unknown as { GlobalWorkerOptions: { workerSrc: string } }).GlobalWorkerOptions.workerSrc = 'https://unpkg.com/pdfjs-dist@4.10.38/build/pdf.worker.min.mjs';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  @ViewChild('pdfWrapper', { static: true }) pdfWrapper!: ElementRef<HTMLDivElement>;
  @ViewChild('fileInput', { static: true }) fileInput!: ElementRef<HTMLInputElement>;
  @ViewChild('imageInput', { static: true }) imageInput!: ElementRef<HTMLInputElement>;

  readonly storageKey = 'pdf-editor-angular-draft-v1';
  tool: Tool = 'select';
  status = 'Nahrajte PDF a vyberte nástroj.';
  currentPage = 0;
  textValue = 'Nový text';
  fontSize = 18;
  imageWidth = 160;
  imageHeight = 120;

  private pdfBytes: Uint8Array | null = null;
  private pdfDoc: PdfJsDocument | null = null;
  private scale = 1.3;
  private pendingPosition?: PendingPlacement;
  items: OverlayItem[] = [];
  selectedItemId: string | null = null;

  constructor() {
    this.tryRestoreDraft();
  }

  setTool(tool: Tool): void {
    this.tool = tool;
    if (tool === 'addText') {
      this.status = 'Klikněte do PDF náhledu na místo, kam chcete vložit text.';
    } else if (tool === 'addImage') {
      this.status = 'Klikněte do PDF náhledu na místo, kam chcete vložit obrázek.';
    } else if (tool === 'replaceText') {
      this.status = 'Klikněte do PDF náhledu na místo, kde chcete přepsat text.';
    } else {
      this.status = 'Aktivní nástroj: výběr.';
    }

    this.renderPages();
  }

  async onPdfSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.items = [];
    this.selectedItemId = null;
    this.currentPage = 0;
    this.pendingPosition = undefined;
    this.pdfBytes = new Uint8Array(await file.arrayBuffer());
    await this.loadPdf(this.pdfBytes);
    this.saveDraft();
  }

  async onImageSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    const dataUrl = await this.toDataUrl(file);
    this.addItem({
      type: 'image',
      imageDataUrl: dataUrl,
      width: this.imageWidth,
      height: this.imageHeight
    });

    input.value = '';
  }

  async generatePdf(): Promise<void> {
    if (!this.pdfBytes) {
      this.status = 'Nejprve nahrajte PDF.';
      return;
    }

    const pdf = await PDFDocument.load(this.pdfBytes);
    const font = await pdf.embedFont(StandardFonts.Helvetica);

    for (const item of this.items) {
      const page = pdf.getPage(item.pageIndex);
      const pageHeight = page.getHeight();

      if (item.type === 'image' && item.imageDataUrl) {
        const imageBytes = await fetch(item.imageDataUrl).then((r) => r.arrayBuffer());
        const embedded = item.imageDataUrl.includes('image/png')
          ? await pdf.embedPng(imageBytes)
          : await pdf.embedJpg(imageBytes);

        const width = item.width ?? 120;
        const height = item.height ?? 120;
        page.drawImage(embedded, {
          x: item.x,
          y: pageHeight - item.y - height,
          width,
          height
        });
        continue;
      }

      const text = item.text ?? '';
      const size = item.fontSize ?? 16;
      if (item.type === 'replace-text') {
        const textWidth = font.widthOfTextAtSize(text, size);
        page.drawRectangle({
          x: item.x - 2,
          y: pageHeight - item.y - size * 1.2,
          width: textWidth + 4,
          height: size * 1.3,
          color: rgb(1, 1, 1)
        });
      }

      page.drawText(text, {
        x: item.x,
        y: pageHeight - item.y - size,
        size,
        font,
        color: rgb(0.1, 0.12, 0.15)
      });
    }

    const output = await pdf.save();
    const blob = new Blob([output], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'upraveny-dokument.pdf';
    link.click();
    URL.revokeObjectURL(url);
    this.status = 'PDF vygenerováno.';
  }

  deleteSelected(): void {
    if (!this.selectedItemId) return;
    this.items = this.items.filter((item) => item.id !== this.selectedItemId);
    this.selectedItemId = null;
    this.renderPages();
    this.saveDraft();
  }

  discardDraft(): void {
    localStorage.removeItem(this.storageKey);
    this.pdfBytes = null;
    this.pdfDoc = null;
    this.items = [];
    this.selectedItemId = null;
    this.pendingPosition = undefined;
    this.pdfWrapper.nativeElement.innerHTML = '';
    this.status = 'Rozpracovaná práce byla smazána.';
    this.fileInput.nativeElement.value = '';
    this.imageInput.nativeElement.value = '';
  }

  onTextInput(value: string): void {
    this.textValue = value;
  }

  onFontSizeChange(delta: number): void {
    this.fontSize = Math.max(8, this.fontSize + delta);
  }

  onImageSizeChange(delta: number): void {
    this.imageWidth = Math.max(40, this.imageWidth + delta);
    this.imageHeight = Math.max(40, this.imageHeight + delta);
  }

  private isPlacementTool(): boolean {
    return this.tool === 'addText' || this.tool === 'replaceText' || this.tool === 'addImage';
  }

  private async loadPdf(bytes: Uint8Array): Promise<void> {
    const loadingTask = (pdfjsLib as unknown as { getDocument: (opts: { data: Uint8Array }) => { promise: Promise<PdfJsDocument> } }).getDocument({ data: bytes });
    this.pdfDoc = await loadingTask.promise;
    this.currentPage = 0;
    await this.renderPages();
    this.status = `Načteno stránek: ${this.pdfDoc.numPages}. Vyberte nástroj a klikněte do PDF.`;
  }

  private async renderPages(): Promise<void> {
    if (!this.pdfDoc) return;
    const wrapper = this.pdfWrapper.nativeElement;
    wrapper.innerHTML = '';

    for (let index = 0; index < this.pdfDoc.numPages; index += 1) {
      const page = await this.pdfDoc.getPage(index + 1);
      const viewport = page.getViewport({ scale: this.scale });

      const pageNode = document.createElement('section');
      pageNode.className = 'page';
      if (this.isPlacementTool()) {
        pageNode.classList.add('placement-mode');
      }
      pageNode.style.width = `${viewport.width}px`;
      pageNode.style.height = `${viewport.height}px`;

      const canvas = document.createElement('canvas');
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      const context = canvas.getContext('2d');
      if (!context) throw new Error('Canvas není dostupný.');

      await page.render({ canvasContext: context, viewport }).promise;
      pageNode.append(canvas);

      const previewCursor = this.buildPlacementCursor();
      pageNode.append(previewCursor);

      pageNode.addEventListener('mousemove', (event) => {
        if (!this.isPlacementTool()) return;
        const rect = pageNode.getBoundingClientRect();
        const x = Math.round((event.clientX - rect.left) / this.scale);
        const y = Math.round((event.clientY - rect.top) / this.scale);
        previewCursor.style.display = 'inline-flex';
        previewCursor.style.left = `${x * this.scale}px`;
        previewCursor.style.top = `${y * this.scale}px`;
      });

      pageNode.addEventListener('mouseleave', () => {
        previewCursor.style.display = 'none';
      });

      pageNode.addEventListener('click', (event) => this.onPageClick(event, index));

      this.items
        .filter((item) => item.pageIndex === index)
        .forEach((item) => pageNode.append(this.buildOverlay(item)));

      wrapper.append(pageNode);
    }
  }

  private buildPlacementCursor(): HTMLDivElement {
    const cursor = document.createElement('div');
    cursor.className = 'placement-cursor';
    cursor.style.display = 'none';

    if (this.tool === 'addImage') {
      cursor.textContent = '🖼';
      cursor.style.width = `${this.imageWidth * this.scale}px`;
      cursor.style.height = `${this.imageHeight * this.scale}px`;
      cursor.style.fontSize = '20px';
    } else {
      cursor.textContent = this.textValue;
      cursor.style.fontSize = `${this.fontSize * this.scale}px`;
      cursor.style.width = 'max-content';
      cursor.style.height = 'auto';
    }

    return cursor;
  }

  private onPageClick(event: MouseEvent, pageIndex: number): void {
    if (!this.pdfDoc) return;
    const target = event.currentTarget as HTMLDivElement;
    const rect = target.getBoundingClientRect();
    const x = Math.round((event.clientX - rect.left) / this.scale);
    const y = Math.round((event.clientY - rect.top) / this.scale);

    this.currentPage = pageIndex;

    if (this.tool === 'addText') {
      this.addItem({ type: 'text', text: this.textValue, fontSize: this.fontSize, x, y });
      return;
    }

    if (this.tool === 'replaceText') {
      this.addItem({ type: 'replace-text', text: this.textValue, fontSize: this.fontSize, x, y });
      return;
    }

    if (this.tool === 'addImage') {
      this.pendingPosition = { x, y, pageIndex };
      this.imageInput.nativeElement.click();
      this.status = 'Vyberte obrázek pro vložení na zvolenou pozici.';
    }
  }

  private addItem(partial: Partial<OverlayItem>): void {
    const item: OverlayItem = {
      id: crypto.randomUUID(),
      type: partial.type ?? 'text',
      pageIndex: this.pendingPosition?.pageIndex ?? this.currentPage,
      x: partial.x ?? this.pendingPosition?.x ?? 40,
      y: partial.y ?? this.pendingPosition?.y ?? 40,
      text: partial.text,
      fontSize: partial.fontSize,
      width: partial.width,
      height: partial.height,
      imageDataUrl: partial.imageDataUrl
    };

    this.pendingPosition = undefined;
    this.items = [...this.items, item];
    this.renderPages();
    this.saveDraft();
  }

  private buildOverlay(item: OverlayItem): HTMLDivElement {
    const node = document.createElement('div');
    node.className = `overlay ${item.id === this.selectedItemId ? 'selected' : ''}`;
    node.style.left = `${item.x * this.scale}px`;
    node.style.top = `${item.y * this.scale}px`;

    if (item.type === 'image' && item.imageDataUrl) {
      const img = document.createElement('img');
      img.src = item.imageDataUrl;
      img.draggable = false;
      img.style.width = `${(item.width ?? 120) * this.scale}px`;
      img.style.height = `${(item.height ?? 120) * this.scale}px`;
      node.append(img);
    } else {
      node.textContent = item.text ?? '';
      node.style.fontSize = `${(item.fontSize ?? 16) * this.scale}px`;
      if (item.type === 'replace-text') {
        node.style.background = 'rgba(255, 255, 255, 0.85)';
      }
    }

    node.addEventListener('click', (ev) => {
      ev.stopPropagation();
      this.selectedItemId = item.id;
      this.renderPages();
    });

    this.enableDragging(node, item);
    return node;
  }

  private enableDragging(node: HTMLDivElement, item: OverlayItem): void {
    let dragging = false;
    let startX = 0;
    let startY = 0;
    let baseX = item.x;
    let baseY = item.y;

    node.addEventListener('pointerdown', (event) => {
      dragging = true;
      startX = event.clientX;
      startY = event.clientY;
      baseX = item.x;
      baseY = item.y;
      node.setPointerCapture(event.pointerId);
    });

    node.addEventListener('pointermove', (event) => {
      if (!dragging) return;
      item.x = Math.max(0, baseX + (event.clientX - startX) / this.scale);
      item.y = Math.max(0, baseY + (event.clientY - startY) / this.scale);
      node.style.left = `${item.x * this.scale}px`;
      node.style.top = `${item.y * this.scale}px`;
    });

    node.addEventListener('pointerup', () => {
      if (!dragging) return;
      dragging = false;
      this.saveDraft();
    });
  }

  private saveDraft(): void {
    if (!this.pdfBytes) return;
    const draft: DraftState = {
      pdfBase64: this.toBase64(this.pdfBytes),
      items: this.items
    };
    localStorage.setItem(this.storageKey, JSON.stringify(draft));
  }

  private tryRestoreDraft(): void {
    const raw = localStorage.getItem(this.storageKey);
    if (!raw) return;

    const restore = window.confirm('Našli jsme rozpracovanou práci. Chcete pokračovat?');
    if (!restore) {
      localStorage.removeItem(this.storageKey);
      return;
    }

    const draft = JSON.parse(raw) as DraftState;
    this.items = draft.items ?? [];
    this.pdfBytes = this.fromBase64(draft.pdfBase64);
    this.loadPdf(this.pdfBytes);
  }

  private toDataUrl(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(new Error('Obrázek nelze načíst.'));
      reader.readAsDataURL(file);
    });
  }

  private toBase64(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i += 1) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary);
  }

  private fromBase64(base64: string): Uint8Array {
    const binary = atob(base64);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      out[i] = binary.charCodeAt(i);
    }
    return out;
  }
}
