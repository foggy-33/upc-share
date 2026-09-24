"""Build the public course catalog bundled with the Spring Boot application."""

import csv
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "courses.csv"
TARGET = ROOT / "springboot/src/main/resources/courses.json"
PUBLIC_TYPES = {"公共选修课", "通识教育课程"}


def clean(value):
    return (value or "").strip()


def main():
    courses = {}
    with SOURCE.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            if clean(row["课程属性"]) not in PUBLIC_TYPES:
                continue
            course_id = clean(row["课程ID"])
            if not course_id:
                continue
            course = courses.setdefault(course_id, {
                "id": course_id,
                "code": clean(row["课程号"]),
                "name": clean(row["课程名称"]),
                "type": clean(row["课程属性"]),
                "semester": clean(row["raw_xnxq01id"]),
                "category": clean(row["raw_szkcflmc"]),
                "credits": clean(row["学分"]),
                "description": clean(row["raw_kcjj"]),
                "core_note": clean(row["raw_xgsm"]).strip('"'),
                "is_art": False,
                "is_core_2": False,
                "campuses": [],
                "teachers": [],
                "offerings": [],
            })
            campus = clean(row["校区"])
            if campus and campus not in course["campuses"]:
                course["campuses"].append(campus)
            teacher = clean(row["教师名称"])
            if teacher and teacher not in course["teachers"]:
                course["teachers"].append(teacher)
            course["is_art"] |= clean(row["raw_tskclx"]) == "艺术课程"
            note = clean(row["raw_xgsm"])
            course["is_core_2"] |= "核心课2.0" in note or "核心2.0" in note or "核心课程2.0" in note
            if not course["description"]:
                course["description"] = clean(row["raw_kcjj"])
            course["offerings"].append({
                "teacher": teacher,
                "campus": campus,
                "time": clean(row["raw_sksj"]),
                "location": clean(row["raw_skdd"]),
                "exam": clean(row["raw_khfs"]),
            })

    result = sorted(courses.values(), key=lambda item: (item["name"], item["code"]))
    TARGET.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {len(result)} courses from {SOURCE.name} to {TARGET}")


if __name__ == "__main__":
    main()
