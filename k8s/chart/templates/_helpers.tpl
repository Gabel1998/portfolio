{{/*
Selector labels — immutable on a Deployment, so kept minimal and stable.
Call with: (dict "name" $name "root" $)
*/}}
{{- define "portfolio.selectorLabels" -}}
app.kubernetes.io/name: portfolio-{{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
Full metadata labels = selector labels + common labels.
Call with: (dict "name" $name "root" $)
*/}}
{{- define "portfolio.labels" -}}
{{ include "portfolio.selectorLabels" . }}
app.kubernetes.io/part-of: portfolio
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
{{- end -}}
