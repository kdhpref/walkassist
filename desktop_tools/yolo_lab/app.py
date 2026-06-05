from __future__ import annotations

import os
import sys
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QPoint, QRect, QRectF, Qt, QProcess, Signal
from PySide6.QtGui import QAction, QColor, QImage, QKeySequence, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QSpinBox,
    QDoubleSpinBox,
    QSplitter,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

try:
    from ultralytics import YOLO
except Exception:
    YOLO = None


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


@dataclass
class BoundingBox:
    class_id: int
    rect: QRectF
    confidence: float = 1.0


class AnnotationCanvas(QWidget):
    annotations_changed = Signal()
    selection_changed = Signal(int)

    def __init__(self) -> None:
        super().__init__()
        self.setMinimumSize(720, 480)
        self.setMouseTracking(True)
        self.image = QImage()
        self.pixmap = QPixmap()
        self.annotations: list[BoundingBox] = []
        self.preview_boxes: list[BoundingBox] = []
        self.class_names: list[str] = []
        self.current_class_id = 0
        self.selected_index = -1
        self._image_rect = QRect()
        self._drag_origin: QPoint | None = None
        self._draft_rect: QRectF | None = None

    def set_current_class(self, class_id: int) -> None:
        self.current_class_id = class_id

    def set_class_names(self, class_names: list[str]) -> None:
        self.class_names = class_names
        self.update()

    def set_image(self, image: QImage) -> None:
        self.image = image
        self.pixmap = QPixmap.fromImage(image) if not image.isNull() else QPixmap()
        self._draft_rect = None
        self.preview_boxes = []
        self.update()

    def set_annotations(self, annotations: list[BoundingBox]) -> None:
        self.annotations = annotations
        self.selected_index = -1
        self.selection_changed.emit(-1)
        self.update()

    def set_preview_boxes(self, preview_boxes: list[BoundingBox]) -> None:
        self.preview_boxes = preview_boxes
        self.update()

    def clear_preview(self) -> None:
        self.preview_boxes = []
        self.update()

    def add_boxes(self, boxes: list[BoundingBox], replace_existing: bool = False) -> None:
        if replace_existing:
            self.annotations = list(boxes)
        else:
            self.annotations.extend(boxes)
        self.preview_boxes = []
        self.selected_index = len(self.annotations) - 1 if self.annotations else -1
        self.selection_changed.emit(self.selected_index)
        self.annotations_changed.emit()
        self.update()

    def delete_selected(self) -> None:
        if 0 <= self.selected_index < len(self.annotations):
            del self.annotations[self.selected_index]
            self.selected_index = -1
            self.selection_changed.emit(-1)
            self.annotations_changed.emit()
            self.update()

    def update_selected_class(self, class_id: int) -> None:
        if 0 <= self.selected_index < len(self.annotations):
            selected = self.annotations[self.selected_index]
            self.annotations[self.selected_index] = BoundingBox(
                class_id=class_id,
                rect=QRectF(selected.rect),
                confidence=selected.confidence,
            )
            self.annotations_changed.emit()
            self.update()

    def paintEvent(self, event) -> None:  # noqa: N802
        del event
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor("#11161d"))

        if self.pixmap.isNull():
            painter.setPen(QColor("#d9e2ea"))
            painter.drawText(self.rect(), Qt.AlignCenter, "이미지를 열어 주세요.")
            return

        scaled = self.pixmap.scaled(self.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation)
        x = (self.width() - scaled.width()) // 2
        y = (self.height() - scaled.height()) // 2
        self._image_rect = QRect(x, y, scaled.width(), scaled.height())
        painter.drawPixmap(self._image_rect, scaled)

        for preview in self.preview_boxes:
            rect = self._to_view_rect(preview.rect)
            painter.setPen(QPen(QColor("#77c3ff"), 2, Qt.DashLine))
            painter.drawRect(rect)
            self._draw_label(
                painter,
                rect,
                f"{self._class_name(preview.class_id)} {int(preview.confidence * 100)}%",
                QColor(12, 24, 38, 215),
            )

        for index, box in enumerate(self.annotations):
            rect = self._to_view_rect(box.rect)
            color = QColor("#ffb648" if index != self.selected_index else "#7ef0a0")
            painter.setPen(QPen(color, 2))
            painter.drawRect(rect)
            self._draw_label(
                painter,
                rect,
                f"{self._class_name(box.class_id)} {int(box.confidence * 100)}%",
                QColor(12, 18, 24, 220),
            )

        if self._draft_rect is not None:
            painter.setPen(QPen(QColor("#8fc8ff"), 2, Qt.DashLine))
            painter.drawRect(self._to_view_rect(self._draft_rect))

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.LeftButton or self.image.isNull():
            return

        image_point = self._to_image_point(event.position().toPoint())
        if image_point is None:
            return

        hit_index = self._hit_test(image_point)
        if hit_index >= 0:
            self.selected_index = hit_index
            self.selection_changed.emit(hit_index)
            self.update()
            return

        self.selected_index = -1
        self.selection_changed.emit(-1)
        self._drag_origin = image_point
        self._draft_rect = QRectF(image_point, image_point).normalized()
        self.update()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self._drag_origin is None or self.image.isNull():
            return
        image_point = self._to_image_point(event.position().toPoint())
        if image_point is None:
            return
        self._draft_rect = QRectF(self._drag_origin, image_point).normalized()
        self.update()

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.LeftButton or self._drag_origin is None or self._draft_rect is None:
            return

        if self._draft_rect.width() >= 10 and self._draft_rect.height() >= 10:
            self.annotations.append(BoundingBox(self.current_class_id, self._clamp_rect(self._draft_rect)))
            self.selected_index = len(self.annotations) - 1
            self.selection_changed.emit(self.selected_index)
            self.annotations_changed.emit()

        self._drag_origin = None
        self._draft_rect = None
        self.update()

    def _hit_test(self, image_point: QPoint) -> int:
        for index in reversed(range(len(self.annotations))):
            if self.annotations[index].rect.contains(image_point):
                return index
        return -1

    def _to_image_point(self, view_point: QPoint) -> QPoint | None:
        if self._image_rect.isNull() or not self._image_rect.contains(view_point):
            return None

        rel_x = (view_point.x() - self._image_rect.left()) / self._image_rect.width()
        rel_y = (view_point.y() - self._image_rect.top()) / self._image_rect.height()
        x = int(rel_x * self.image.width())
        y = int(rel_y * self.image.height())
        return QPoint(x, y)

    def _to_view_rect(self, image_rect: QRectF) -> QRectF:
        if self._image_rect.isNull():
            return QRectF()
        scale_x = self._image_rect.width() / self.image.width()
        scale_y = self._image_rect.height() / self.image.height()
        return QRectF(
            self._image_rect.left() + image_rect.left() * scale_x,
            self._image_rect.top() + image_rect.top() * scale_y,
            image_rect.width() * scale_x,
            image_rect.height() * scale_y,
        )

    def _clamp_rect(self, rect: QRectF) -> QRectF:
        return QRectF(
            max(0.0, rect.left()),
            max(0.0, rect.top()),
            min(float(self.image.width()), rect.right()),
            min(float(self.image.height()), rect.bottom()),
        ).normalized()

    def _draw_label(self, painter: QPainter, rect: QRectF, text: str, background: QColor) -> None:
        label_rect = QRect(int(rect.left()), max(0, int(rect.top()) - 24), 180, 20)
        painter.fillRect(label_rect, background)
        painter.setPen(QColor("#ffffff"))
        painter.drawText(label_rect.adjusted(6, 0, -6, 0), Qt.AlignLeft | Qt.AlignVCenter, text)

    def _class_name(self, class_id: int) -> str:
        if 0 <= class_id < len(self.class_names):
            return self.class_names[class_id]
        return f"class_{class_id}"


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("WalkAssist YOLO Lab")
        self.resize(1420, 900)

        self.image_dir: Path | None = None
        self.label_dir: Path | None = None
        self.dataset_root: Path | None = None
        self.model_path: Path | None = None
        self.loaded_model: object | None = None
        self.loaded_model_path: str | None = None
        self.classes: list[str] = [
            "person",
            "stairs_up",
            "stairs_down",
            "traffic_light_red",
            "traffic_light_green",
        ]
        self.current_image_path: Path | None = None
        self.process: QProcess | None = None

        self.canvas = AnnotationCanvas()
        self.canvas.annotations_changed.connect(self._on_annotations_changed)
        self.canvas.selection_changed.connect(self._on_selection_changed)
        self.canvas.set_class_names(self.classes)

        self.image_dir_edit = QLineEdit()
        self.label_dir_edit = QLineEdit()
        self.dataset_root_edit = QLineEdit()
        self.model_edit = QLineEdit("yolo26n-seg.pt")
        self.yaml_edit = QLineEdit()

        self.image_list = QListWidget()
        self.image_list.currentItemChanged.connect(self._on_image_selected)
        self.class_combo = QComboBox()
        self.class_combo.currentIndexChanged.connect(self._on_class_changed)
        self.selected_box_label = QLabel("선택된 박스: 없음")
        self._refresh_class_combo()

        self.log_output = QPlainTextEdit()
        self.log_output.setReadOnly(True)

        self.epochs_spin = QSpinBox()
        self.epochs_spin.setRange(1, 500)
        self.epochs_spin.setValue(50)

        self.batch_spin = QSpinBox()
        self.batch_spin.setRange(1, 128)
        self.batch_spin.setValue(16)

        self.imgsz_spin = QSpinBox()
        self.imgsz_spin.setRange(64, 2048)
        self.imgsz_spin.setSingleStep(32)
        self.imgsz_spin.setValue(640)

        self.device_edit = QLineEdit("0")
        self.export_format_combo = QComboBox()
        self.export_format_combo.addItems(["tflite", "onnx", "saved_model"])

        self.threshold_spin = QDoubleSpinBox()
        self.threshold_spin.setRange(0.05, 0.95)
        self.threshold_spin.setSingleStep(0.05)
        self.threshold_spin.setValue(0.35)

        self.max_det_spin = QSpinBox()
        self.max_det_spin.setRange(1, 200)
        self.max_det_spin.setValue(20)

        self._build_ui()
        self._install_shortcuts()

    def _build_ui(self) -> None:
        tab_widget = QTabWidget()
        tab_widget.addTab(self._build_labeling_tab(), "라벨링")
        tab_widget.addTab(self._build_training_tab(), "학습 / Export")
        self.setCentralWidget(tab_widget)

    def _build_labeling_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        path_row = QGridLayout()
        path_row.addWidget(QLabel("이미지 폴더"), 0, 0)
        path_row.addWidget(self.image_dir_edit, 0, 1)
        image_btn = QPushButton("열기")
        image_btn.clicked.connect(self._choose_image_dir)
        path_row.addWidget(image_btn, 0, 2)

        path_row.addWidget(QLabel("라벨 폴더"), 1, 0)
        path_row.addWidget(self.label_dir_edit, 1, 1)
        label_btn = QPushButton("열기")
        label_btn.clicked.connect(self._choose_label_dir)
        path_row.addWidget(label_btn, 1, 2)

        path_row.addWidget(QLabel("클래스"), 2, 0)
        path_row.addWidget(self.class_combo, 2, 1)
        class_btn = QPushButton("클래스 편집")
        class_btn.clicked.connect(self._edit_classes)
        path_row.addWidget(class_btn, 2, 2)
        apply_class_btn = QPushButton("선택 박스 클래스 적용")
        apply_class_btn.clicked.connect(self._apply_selected_class)
        path_row.addWidget(apply_class_btn, 2, 3)

        path_row.addWidget(QLabel("자동 박스 threshold"), 3, 0)
        path_row.addWidget(self.threshold_spin, 3, 1)
        path_row.addWidget(QLabel("최대 검출 수"), 3, 2)
        path_row.addWidget(self.max_det_spin, 3, 3)
        layout.addLayout(path_row)
        layout.addWidget(self.selected_box_label)

        splitter = QSplitter()
        splitter.addWidget(self.image_list)
        splitter.addWidget(self.canvas)
        splitter.setSizes([300, 980])
        layout.addWidget(splitter, stretch=1)

        button_row = QHBoxLayout()
        prev_btn = QPushButton("이전")
        prev_btn.clicked.connect(self._select_previous_image)
        next_btn = QPushButton("다음")
        next_btn.clicked.connect(self._select_next_image)
        save_btn = QPushButton("저장")
        save_btn.clicked.connect(self._save_current_annotations)
        delete_btn = QPushButton("선택 박스 삭제")
        delete_btn.clicked.connect(self.canvas.delete_selected)
        autoset_btn = QPushButton("라벨 폴더 자동 설정")
        autoset_btn.clicked.connect(self._auto_configure_label_dir)
        infer_btn = QPushButton("테스트 추론")
        infer_btn.clicked.connect(self._preview_model_predictions)
        apply_btn = QPushButton("자동 박스 추가")
        apply_btn.clicked.connect(self._apply_model_predictions)
        clear_preview_btn = QPushButton("미리보기 제거")
        clear_preview_btn.clicked.connect(self.canvas.clear_preview)

        for button in [prev_btn, next_btn, save_btn, delete_btn, autoset_btn, infer_btn, apply_btn, clear_preview_btn]:
            button_row.addWidget(button)
        button_row.addStretch(1)
        layout.addLayout(button_row)

        helper = QLabel(
            "추천: 신호등 상태 분류가 필요하면 `traffic_light_red`, `traffic_light_green`처럼 상태별 클래스로 직접 라벨링하세요."
        )
        helper.setStyleSheet("color: #cfd8e3;")
        layout.addWidget(helper)
        return page

    def _build_training_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)

        config_group = QGroupBox("학습 설정")
        config_form = QFormLayout(config_group)

        dataset_row = self._path_row(self.dataset_root_edit, self._choose_dataset_root)
        yaml_row = self._path_row(self.yaml_edit, self._choose_yaml_path)
        model_row = self._path_row(self.model_edit, self._choose_model_path)

        config_form.addRow("데이터셋 루트", dataset_row)
        config_form.addRow("dataset.yaml", yaml_row)
        config_form.addRow("기준 모델", model_row)
        config_form.addRow("Epoch", self.epochs_spin)
        config_form.addRow("Batch", self.batch_spin)
        config_form.addRow("Image Size", self.imgsz_spin)
        config_form.addRow("Device", self.device_edit)
        config_form.addRow("Export Format", self.export_format_combo)
        layout.addWidget(config_group)

        action_row = QHBoxLayout()
        yaml_btn = QPushButton("dataset.yaml 생성")
        yaml_btn.clicked.connect(self._generate_dataset_yaml)
        train_btn = QPushButton("학습 실행")
        train_btn.clicked.connect(self._run_training)
        export_btn = QPushButton("Export 실행")
        export_btn.clicked.connect(self._run_export)
        stop_btn = QPushButton("중지")
        stop_btn.clicked.connect(self._stop_process)

        for button in [yaml_btn, train_btn, export_btn, stop_btn]:
            action_row.addWidget(button)
        action_row.addStretch(1)
        layout.addLayout(action_row)

        layout.addWidget(QLabel("실행 로그"))
        layout.addWidget(self.log_output, stretch=1)
        return page

    def _install_shortcuts(self) -> None:
        save_action = QAction(self)
        save_action.setShortcut(QKeySequence.Save)
        save_action.triggered.connect(self._save_current_annotations)
        self.addAction(save_action)

        delete_action = QAction(self)
        delete_action.setShortcut(QKeySequence.Delete)
        delete_action.triggered.connect(self.canvas.delete_selected)
        self.addAction(delete_action)

    def _path_row(self, line_edit: QLineEdit, handler) -> QWidget:
        widget = QWidget()
        layout = QHBoxLayout(widget)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.addWidget(line_edit)
        button = QPushButton("찾기")
        button.clicked.connect(handler)
        layout.addWidget(button)
        return widget

    def _choose_image_dir(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "이미지 폴더 선택")
        if not path:
            return
        self.image_dir = Path(path)
        self.image_dir_edit.setText(path)
        self._load_image_list()

    def _choose_label_dir(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "라벨 폴더 선택")
        if not path:
            return
        self.label_dir = Path(path)
        self.label_dir_edit.setText(path)
        self._load_current_annotations()

    def _choose_dataset_root(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "데이터셋 루트 선택")
        if path:
            self.dataset_root = Path(path)
            self.dataset_root_edit.setText(path)

    def _choose_model_path(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "기준 모델 선택", filter="PyTorch Model (*.pt)")
        if path:
            self.model_path = Path(path)
            self.model_edit.setText(path)
            self.loaded_model = None
            self.loaded_model_path = None

    def _choose_yaml_path(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "dataset.yaml 선택", filter="YAML (*.yaml)")
        if path:
            self.yaml_edit.setText(path)

    def _load_image_list(self) -> None:
        self.image_list.clear()
        if self.image_dir is None:
            return
        for image_path in sorted(self.image_dir.iterdir()):
            if image_path.suffix.lower() in IMAGE_EXTENSIONS and image_path.is_file():
                item = QListWidgetItem(image_path.name)
                item.setData(Qt.UserRole, str(image_path))
                self.image_list.addItem(item)
        if self.image_list.count() > 0:
            self.image_list.setCurrentRow(0)

    def _on_image_selected(self, current: QListWidgetItem | None, previous: QListWidgetItem | None) -> None:
        del previous
        if current is None:
            return
        image_path = Path(current.data(Qt.UserRole))
        self.current_image_path = image_path
        image = QImage(str(image_path))
        if image.isNull():
            QMessageBox.warning(self, "이미지 오류", f"이미지를 열 수 없습니다.\n{image_path}")
            return
        self.canvas.set_image(image)
        self._load_current_annotations()

    def _load_current_annotations(self) -> None:
        if self.current_image_path is None or self.label_dir is None or self.canvas.image.isNull():
            self.canvas.set_annotations([])
            return

        label_path = self.label_dir / f"{self.current_image_path.stem}.txt"
        annotations: list[BoundingBox] = []
        if label_path.exists():
            for line in label_path.read_text(encoding="utf-8").splitlines():
                parts = line.strip().split()
                if len(parts) != 5:
                    continue
                class_id, x_center, y_center, width, height = parts
                rect = self._yolo_to_rect(
                    float(x_center),
                    float(y_center),
                    float(width),
                    float(height),
                    self.canvas.image.width(),
                    self.canvas.image.height(),
                )
                annotations.append(BoundingBox(int(class_id), rect))
        self.canvas.set_annotations(annotations)

    def _save_current_annotations(self) -> None:
        if self.current_image_path is None or self.label_dir is None or self.canvas.image.isNull():
            QMessageBox.information(self, "저장 불가", "이미지와 라벨 폴더를 먼저 선택해 주세요.")
            return

        self.label_dir.mkdir(parents=True, exist_ok=True)
        label_path = self.label_dir / f"{self.current_image_path.stem}.txt"
        lines = []
        for box in self.canvas.annotations:
            x_center, y_center, width, height = self._rect_to_yolo(
                box.rect,
                self.canvas.image.width(),
                self.canvas.image.height(),
            )
            lines.append(f"{box.class_id} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}")
        label_path.write_text("\n".join(lines), encoding="utf-8")
        self.statusBar().showMessage(f"저장 완료: {label_path}", 3000)

    def _on_annotations_changed(self) -> None:
        self.statusBar().showMessage("라벨이 변경되었습니다. Ctrl+S로 저장하세요.", 3000)

    def _on_class_changed(self) -> None:
        self.canvas.set_current_class(self.class_combo.currentData() or 0)

    def _on_selection_changed(self, index: int) -> None:
        if 0 <= index < len(self.canvas.annotations):
            selected = self.canvas.annotations[index]
            class_name = self.classes[selected.class_id] if 0 <= selected.class_id < len(self.classes) else f"class_{selected.class_id}"
            self.selected_box_label.setText(
                f"선택된 박스: {index + 1}번 / {class_name} / {int(selected.confidence * 100)}%"
            )
        else:
            self.selected_box_label.setText("선택된 박스: 없음")

    def _apply_selected_class(self) -> None:
        if self.canvas.selected_index < 0:
            QMessageBox.information(self, "선택 필요", "먼저 변경할 박스를 클릭해 주세요.")
            return
        class_id = self.class_combo.currentData()
        if class_id is None:
            QMessageBox.information(self, "선택 필요", "적용할 클래스를 먼저 선택해 주세요.")
            return
        self.canvas.update_selected_class(int(class_id))
        self._on_selection_changed(self.canvas.selected_index)
        self.statusBar().showMessage("선택한 박스의 클래스를 변경했습니다.", 3000)

    def _select_previous_image(self) -> None:
        row = self.image_list.currentRow()
        if row > 0:
            self.image_list.setCurrentRow(row - 1)

    def _select_next_image(self) -> None:
        row = self.image_list.currentRow()
        if row < self.image_list.count() - 1:
            self.image_list.setCurrentRow(row + 1)

    def _auto_configure_label_dir(self) -> None:
        if self.image_dir is None:
            return
        if self.image_dir.parent.name == "images":
            candidate = self.image_dir.parent.parent / "labels" / self.image_dir.name
        else:
            candidate = self.image_dir.parent / "labels"
        self.label_dir = candidate
        self.label_dir_edit.setText(str(candidate))
        self._load_current_annotations()

    def _edit_classes(self) -> None:
        text, ok = QInputDialog.getMultiLineText(
            self,
            "클래스 편집",
            "클래스를 한 줄에 하나씩 입력하세요.",
            "\n".join(self.classes),
        )
        if not ok:
            return
        classes = [line.strip() for line in text.splitlines() if line.strip()]
        if not classes:
            QMessageBox.information(self, "입력 필요", "최소 1개 이상의 클래스를 입력해 주세요.")
            return
        self.classes = classes
        self._refresh_class_combo()
        self.statusBar().showMessage("클래스 목록을 갱신했습니다.", 3000)

    def _refresh_class_combo(self) -> None:
        self.class_combo.blockSignals(True)
        self.class_combo.clear()
        for index, name in enumerate(self.classes):
            self.class_combo.addItem(f"{index}: {name}", index)
        self.class_combo.blockSignals(False)
        self.canvas.set_class_names(self.classes)
        self.canvas.set_current_class(self.class_combo.currentData() or 0)

    def _generate_dataset_yaml(self) -> None:
        if not self.dataset_root_edit.text().strip():
            QMessageBox.information(self, "입력 필요", "데이터셋 루트를 먼저 선택해 주세요.")
            return
        dataset_root = Path(self.dataset_root_edit.text().strip())
        yaml_path = Path(self.yaml_edit.text().strip()) if self.yaml_edit.text().strip() else dataset_root / "dataset.yaml"
        yaml_text = "\n".join(
            [
                f"path: {dataset_root.as_posix()}",
                "train: images/train",
                "val: images/val",
                "test: images/test",
                "",
                f"names: {self.classes}",
            ]
        )
        yaml_path.write_text(yaml_text, encoding="utf-8")
        self.yaml_edit.setText(str(yaml_path))
        self.statusBar().showMessage(f"dataset.yaml 생성 완료: {yaml_path}", 4000)

    def _run_training(self) -> None:
        yaml_path = self.yaml_edit.text().strip()
        if not yaml_path:
            QMessageBox.warning(self, "실행 불가", "dataset.yaml 경로를 먼저 지정해 주세요.")
            return
        command = (
            f"yolo detect train model=\"{self.model_edit.text().strip()}\" "
            f"data=\"{yaml_path}\" epochs={self.epochs_spin.value()} "
            f"imgsz={self.imgsz_spin.value()} batch={self.batch_spin.value()} "
            f"device={self.device_edit.text().strip()}"
        )
        self._start_process(command)

    def _run_export(self) -> None:
        model_path = self.model_edit.text().strip()
        if not model_path:
            QMessageBox.warning(self, "실행 불가", "export 할 모델 경로를 먼저 지정해 주세요.")
            return
        command = (
            f"yolo export model=\"{model_path}\" "
            f"format={self.export_format_combo.currentText()} imgsz={self.imgsz_spin.value()}"
        )
        self._start_process(command)

    def _preview_model_predictions(self) -> None:
        predictions = self._predict_current_image()
        if predictions is None:
            return
        self.canvas.set_preview_boxes(predictions)
        self.log_output.appendPlainText(f"테스트 추론: {len(predictions)}개 박스를 미리보기로 표시했습니다.")

    def _apply_model_predictions(self) -> None:
        predictions = self._predict_current_image()
        if predictions is None:
            return
        if not predictions:
            QMessageBox.information(self, "자동 박스 추가", "threshold를 넘는 예측이 없습니다.")
            return
        self.canvas.add_boxes(predictions, replace_existing=False)
        self.log_output.appendPlainText(f"자동 박스 추가: {len(predictions)}개 박스를 현재 라벨에 추가했습니다.")

    def _predict_current_image(self) -> list[BoundingBox] | None:
        if self.current_image_path is None or self.canvas.image.isNull():
            QMessageBox.information(self, "실행 불가", "먼저 이미지를 선택해 주세요.")
            return None
        model = self._get_or_load_model()
        if model is None:
            return None

        try:
            results = model.predict(
                source=str(self.current_image_path),
                conf=float(self.threshold_spin.value()),
                imgsz=int(self.imgsz_spin.value()),
                max_det=int(self.max_det_spin.value()),
                verbose=False,
            )
        except Exception as exc:
            QMessageBox.warning(self, "추론 오류", str(exc))
            return None

        predictions: list[BoundingBox] = []
        if not results:
            return predictions

        result = results[0]
        boxes = getattr(result, "boxes", None)
        if boxes is None:
            return predictions

        xyxy_list = boxes.xyxy.tolist() if hasattr(boxes, "xyxy") else []
        cls_list = boxes.cls.tolist() if hasattr(boxes, "cls") else []
        conf_list = boxes.conf.tolist() if hasattr(boxes, "conf") else []

        for xyxy, cls_id, confidence in zip(xyxy_list, cls_list, conf_list):
            class_name = self._resolve_predicted_name(model, int(cls_id))
            mapped_class = self._find_or_append_class(class_name)
            rect = QRectF(
                float(xyxy[0]),
                float(xyxy[1]),
                float(xyxy[2] - xyxy[0]),
                float(xyxy[3] - xyxy[1]),
            )
            predictions.append(BoundingBox(mapped_class, rect, float(confidence)))

        self._refresh_class_combo()
        self.canvas.set_class_names(self.classes)
        return predictions

    def _get_or_load_model(self):
        if YOLO is None:
            QMessageBox.warning(self, "모델 로드 불가", "ultralytics 패키지가 설치되어 있지 않습니다.")
            return None

        model_path = self.model_edit.text().strip()
        if not model_path:
            QMessageBox.information(self, "입력 필요", "기준 모델 경로를 먼저 지정해 주세요.")
            return None

        if self.loaded_model is not None and self.loaded_model_path == model_path:
            return self.loaded_model

        try:
            self.loaded_model = YOLO(model_path)
            self.loaded_model_path = model_path
            self.log_output.appendPlainText(f"모델 로드 완료: {model_path}")
            return self.loaded_model
        except Exception as exc:
            QMessageBox.warning(self, "모델 로드 오류", str(exc))
            self.loaded_model = None
            self.loaded_model_path = None
            return None

    def _resolve_predicted_name(self, model, class_id: int) -> str:
        names = getattr(model, "names", {})
        if isinstance(names, dict):
            return str(names.get(class_id, f"class_{class_id}"))
        if isinstance(names, list) and 0 <= class_id < len(names):
            return str(names[class_id])
        return f"class_{class_id}"

    def _find_or_append_class(self, class_name: str) -> int:
        if class_name in self.classes:
            return self.classes.index(class_name)
        self.classes.append(class_name)
        return len(self.classes) - 1

    def _start_process(self, command: str) -> None:
        if self.process is not None and self.process.state() != QProcess.NotRunning:
            QMessageBox.information(self, "실행 중", "이미 다른 작업이 실행 중입니다.")
            return

        self.log_output.appendPlainText(f"> {command}")
        process = QProcess(self)
        process.setProgram("cmd.exe")
        process.setArguments(["/c", command])
        process.setProcessChannelMode(QProcess.MergedChannels)
        process.readyReadStandardOutput.connect(lambda: self._append_process_output(process))
        process.finished.connect(self._process_finished)
        process.start()
        self.process = process

    def _append_process_output(self, process: QProcess) -> None:
        text = bytes(process.readAllStandardOutput()).decode("utf-8", errors="ignore")
        if text:
            self.log_output.appendPlainText(text.rstrip())

    def _process_finished(self) -> None:
        self.log_output.appendPlainText("작업이 종료되었습니다.")

    def _stop_process(self) -> None:
        if self.process is not None and self.process.state() != QProcess.NotRunning:
            self.process.kill()
            self.log_output.appendPlainText("작업을 중지했습니다.")

    def _rect_to_yolo(self, rect: QRectF, image_width: int, image_height: int) -> tuple[float, float, float, float]:
        x_center = ((rect.left() + rect.right()) * 0.5) / image_width
        y_center = ((rect.top() + rect.bottom()) * 0.5) / image_height
        width = rect.width() / image_width
        height = rect.height() / image_height
        return x_center, y_center, width, height

    def _yolo_to_rect(
        self,
        x_center: float,
        y_center: float,
        width: float,
        height: float,
        image_width: int,
        image_height: int,
    ) -> QRectF:
        box_width = width * image_width
        box_height = height * image_height
        center_x = x_center * image_width
        center_y = y_center * image_height
        return QRectF(
            center_x - box_width * 0.5,
            center_y - box_height * 0.5,
            box_width,
            box_height,
        )


def main() -> int:
    os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "1")
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
