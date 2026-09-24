# 公选课数据与评价

课程目录来自项目根目录的 `courses.csv`。执行以下命令重新生成应用内的课程快照：

```bash
python3 deploy/build_courses.py
docker compose up -d --build app
```

生成结果是 `springboot/src/main/resources/courses.json`。构建镜像时该文件会进入 Spring Boot JAR。CSV 中的公共选修课和通识教育课程按课程 ID 合并；不同教师、校区和时间保留为同一课程的不同教学班。艺术课程取 `raw_tskclx`，2.0 核心课程取 `raw_xgsm` 中明确的“核心课2.0”或“核心2.0”标记。CSV 是学期快照，课程安排应以教务系统为准。

用户评价保存在 MySQL 的 `course_reviews` 表中，重建课程快照不会删除已有评价。每个用户对每门课程保留一条可修改的 1–10 分评分和最多 1000 字的评价。应用启动时通过 `schema.sql` 创建评价表。
