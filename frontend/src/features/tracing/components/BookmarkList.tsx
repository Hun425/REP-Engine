import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2, Trash2, Edit2, Check, X } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { getBookmarks, deleteBookmark, updateBookmarkNote } from '@/api/tracing'

interface BookmarkListProps {
  onSelectTrace: (traceId: string) => void
}

export function BookmarkList({ onSelectTrace }: BookmarkListProps) {
  const queryClient = useQueryClient()
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editNote, setEditNote] = useState('')

  const { data: bookmarks = [], isLoading } = useQuery({
    queryKey: ['bookmarks'],
    queryFn: getBookmarks,
  })

  const deleteMutation = useMutation({
    mutationFn: deleteBookmark,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['bookmarks'] })
    },
  })

  const updateNoteMutation = useMutation({
    mutationFn: ({ id, note }: { id: string; note: string }) => updateBookmarkNote(id, note),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['bookmarks'] })
      setEditingId(null)
    },
  })

  const startEdit = (id: string, currentNote?: string) => {
    setEditingId(id)
    setEditNote(currentNote || '')
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">Bookmarks ({bookmarks.length})</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        ) : bookmarks.length === 0 ? (
          <p className="text-center py-8 text-muted-foreground text-sm">
            No bookmarks yet. View a trace and click Bookmark to save it.
          </p>
        ) : (
          <div className="space-y-2">
            {bookmarks.map(bookmark => (
              <div
                key={bookmark.id}
                className="p-3 border rounded-lg hover:bg-muted/30 transition-colors"
              >
                <div className="flex items-center justify-between">
                  <div
                    className="flex-1 cursor-pointer"
                    onClick={() => onSelectTrace(bookmark.traceId)}
                  >
                    <div className="flex items-center gap-2 text-sm">
                      <span className="font-medium">{bookmark.serviceName}</span>
                      <span className="text-muted-foreground">{bookmark.operationName}</span>
                      <span className="font-mono text-xs text-muted-foreground">
                        {bookmark.durationMs}ms
                      </span>
                    </div>
                    <p className="text-xs text-muted-foreground mt-0.5 font-mono">
                      {bookmark.traceId.substring(0, 16)}...
                    </p>
                  </div>

                  <div className="flex items-center gap-1 shrink-0">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0"
                      onClick={(e) => {
                        e.stopPropagation()
                        startEdit(bookmark.id, bookmark.note)
                      }}
                    >
                      <Edit2 className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0 text-destructive"
                      onClick={(e) => {
                        e.stopPropagation()
                        deleteMutation.mutate(bookmark.id)
                      }}
                      disabled={deleteMutation.isPending}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                </div>

                {/* Note editing */}
                {editingId === bookmark.id ? (
                  <div className="flex items-center gap-2 mt-2">
                    <Input
                      value={editNote}
                      onChange={e => setEditNote(e.target.value)}
                      placeholder="Add a note..."
                      className="h-7 text-xs"
                      autoFocus
                    />
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0"
                      onClick={() => updateNoteMutation.mutate({ id: bookmark.id, note: editNote })}
                    >
                      <Check className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0"
                      onClick={() => setEditingId(null)}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                ) : bookmark.note ? (
                  <p className="text-xs text-muted-foreground mt-1 italic">{bookmark.note}</p>
                ) : null}

                <p className="text-[10px] text-muted-foreground mt-1">
                  {new Date(bookmark.createdAt).toLocaleString()}
                </p>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
