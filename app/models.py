"""
数据模型
"""
from dataclasses import dataclass


@dataclass
class FileRecord:
    id: str
    file_path: str
    original_name: str
    extension: str
    file_size: int
    description: str
    category: str
    sub_category: str
    created_at: str
    download_count: int
    status: str = "pending"
    uploader: str = ""

    @classmethod
    def from_row(cls, row):
        return cls(
            id=row["id"],
            file_path=row["file_path"],
            original_name=row["original_name"],
            extension=row["extension"],
            file_size=row["file_size"],
            description=row["description"],
            category=row["category"],
            sub_category=row["sub_category"],
            created_at=row["created_at"],
            download_count=row["download_count"],
            status=row["status"] if "status" in row.keys() else "approved",
            uploader=row["uploader"] if "uploader" in row.keys() else "",
        )

    def to_dict(self):
        return {
            "id": self.id,
            "file_path": self.file_path,
            "original_name": self.original_name,
            "extension": self.extension,
            "file_size": self.format_size(),
            "file_size_raw": self.file_size,
            "description": self.description,
            "category": self.category,
            "sub_category": self.sub_category,
            "created_at": self.created_at,
            "download_count": self.download_count,
            "status": self.status,
            "uploader": self.uploader,
        }

    def format_size(self) -> str:
        """格式化文件大小"""
        s = float(self.file_size)
        for unit in ("B", "KB", "MB", "GB"):
            if s < 1024:
                return f"{s:.1f} {unit}"
            s /= 1024
        return f"{s:.1f} TB"
